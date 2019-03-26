package com.vitgon.httpserver.request;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.vitgon.httpserver.Server;
import com.vitgon.httpserver.data.Cookie;
import com.vitgon.httpserver.data.Header;
import com.vitgon.httpserver.data.Part;
import com.vitgon.httpserver.data.RequestParameter;
import com.vitgon.httpserver.data.RequestParameters;
import com.vitgon.httpserver.enums.HttpMethod;
import com.vitgon.httpserver.exception.RequestTooBigException;
import com.vitgon.httpserver.util.ByteUtil;
import com.vitgon.httpserver.util.FileUtil;

public class RequestProcessor {
	private ByteBuffer tempBuffer = ByteBuffer.allocate(1024);
	private ByteBuffer requestBuffer = ByteBuffer.allocate(Server.getServerParameters().getMaxPostSize());
	
	private int bytesRemain;
	private boolean headerReceived;
	private boolean bodyReceived;
	private int headerBlockLastBytePosition;
	private int contentLength;
	
	private RequestHeaderFactory headerFactory;
	private RequestBodyFactory bodyFactory;
	
	private static final int CARRIAGE_RETURN = 13; // \r
	private static final int NEW_LINE = 10; // \n
	
	public RequestProcessor() {
		headerFactory = new RequestHeaderFactory();
		bodyFactory = new RequestBodyFactory();
	}
	
	public Request readRequest(SelectionKey key) throws IOException, RequestTooBigException {
		SocketChannel client = (SocketChannel) key.channel();
		
		// assume that we didn't flip requestBuffer
		int requestBufferSize = requestBuffer.position();
		
		if (requestBufferSize == 0) {
			tempBuffer.clear();
			int read = 0;
			while ((read = client.read(tempBuffer)) > 0) {
				tempBuffer.flip();
				byte[] bytes = new byte[tempBuffer.limit()];
				tempBuffer.get(bytes);
				tempBuffer.clear();
				addToRequestBuffer(bytes);
			}
		}
		
		if (!headerReceived) {
			parseHeader();
		}
		
		// check for test reason (see variable value in debug)
		String headerStr = new String(headerFactory.getHeaderData());
		
		// if we did not get full header
		if (headerReceived == false) return null;
		
		// if request method is POST we should expect body
		if (shouldExpectBody()) {
			if (contentLength == 0) {
				contentLength = getContentLength();
				checkContentLength();
				bodyFactory.initialize(contentLength);
				bytesRemain = contentLength;
			}
			
			// if full body was received
			if (bytesRemain > 0) {
				getBody();
				
				// in case if we received only part of body
				// continue reading from SelectionKey
				if (bytesRemain > 0) {
					return null;
				} else {
					parseBody();
				}
			} else {
				parseBody();
			}
		}
		
		// we MUST generate request
		// TODO: set headers, method, uri and etc.
		Request request = new Request();
		if (request.getHeader("Cookie") != null) {
			parseCookie(request.getHeader("Cookie"), request);
		}
		
		return request;
	}
	
	private void parseHeader() {
		byte[] requestData = requestBuffer.array();
		int lastHeaderBytePosition = 0;
		boolean parsedFirstString = false;
		for (int i = 0; i < requestData.length; i++) {
			if (i == 0) {
				continue;
			}
			
			if ((requestData[i - 1] == CARRIAGE_RETURN) && (requestData[i] == NEW_LINE)) {
				
				String line = new String(requestData, lastHeaderBytePosition, i - lastHeaderBytePosition - 1);
				
				// check if we parsed the first line (GET / HTTP/1.1)
				if (!parsedFirstString) {
					parsedFirstString = true;
					parseFirstLine(line);
				} else {
					int headerNameEndPosition = line.indexOf(":"); 
					String name = line.substring(0, headerNameEndPosition);
					String value = line.substring(headerNameEndPosition + 2, line.length());
					
					headerFactory.addHeader(new Header(name, value));
				}
				
				lastHeaderBytePosition = (i + 1);
				
				// if we reached header block end
				if (requestData[i+1] == CARRIAGE_RETURN && requestData[i+2] == NEW_LINE) {
					headerBlockLastBytePosition = i + 2;
					headerReceived = true;
					headerFactory.addHeaderData(Arrays.copyOfRange(requestData, 0, headerBlockLastBytePosition));
					break;
				}
			}
		}
	}
	
	private void getBody() throws RequestTooBigException, IOException {
		int receivedRequestBytesLength = requestBuffer.position();
		int receviedBodyBytesLength = receivedRequestBytesLength - (headerBlockLastBytePosition + 1);
		
		byte[] requestByteArr = requestBuffer.array();
		byte[] requestBodyPart = new byte[receviedBodyBytesLength];
		System.arraycopy(requestByteArr, headerBlockLastBytePosition + 1, requestBodyPart, 0, receviedBodyBytesLength);
		bodyFactory.addBodyData(requestBodyPart);
		bytesRemain = bytesRemain - receviedBodyBytesLength;
		
		FileUtil.saveToFile("src/main/resources/requestBody.txt", requestBodyPart);
		FileUtil.saveToFile("src/main/resources/request.txt", requestByteArr);
	}
	
	private void parseFirstLine(String line) {
		String[] lineParts = line.split(" ");
		headerFactory.setMethod(HttpMethod.valueOf(lineParts[0]));
		headerFactory.setUri(lineParts[1]);
		headerFactory.setHttpVersion(lineParts[2]);
	}
	
	private void parseCookie(String cookiesStr, Request request) {

		// parse cookies
		int cookiePointer = 0;
		for (int i = 0; i < cookiesStr.length(); i++) {
			int curCookieEndPosition = cookiesStr.indexOf(";", i);

			String curCookie;
			if (curCookieEndPosition == -1) {
				curCookie = cookiesStr;
				i = cookiesStr.length();
			} else {
				curCookie = cookiesStr.substring(cookiePointer, curCookieEndPosition);
				i = curCookieEndPosition;
			}

			String[] curCookieNameValueParts = curCookie.split("=");
			String curCookieName = curCookieNameValueParts[0];
			String curCookieValue = curCookieNameValueParts[1];
			
			request.getCookies().add(new Cookie(curCookieName, curCookieValue));
		}
	}
	
	private void parseBody() {
		if (headerFactory.getHeader("Content-Type") != null) {
			String contentType = getHeaderFirstValue(headerFactory.getHeader("Content-Type"));
			
			if (contentType.equals("application/x-www-form-urlencoded")) {
				parseUrlEncodedBody();
			} else if (contentType.equals("multipart/form-data")) {
				parseMultipartFormData();
			}
		}
	}
	
	private void parseMultipartFormData() {
		byte[] bodyData = bodyFactory.getBodyData();
		String boundary = headerFactory.getBoundary();
		byte[] delimeterBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
		
		int lastDelimeterStartPos = 0;
		int lastPartEndPos = 0;
		while ((lastDelimeterStartPos = ByteUtil.indexOf(bodyData, lastPartEndPos, delimeterBytes)) != -1) {
			int partStartPosition = lastDelimeterStartPos + delimeterBytes.length + 2;
			int nextDelimeterStartPos = ByteUtil.indexOf(bodyData, partStartPosition, delimeterBytes);
			
			byte[] partData = Arrays.copyOfRange(bodyData, partStartPosition, nextDelimeterStartPos);
			bodyFactory.addPart(new Part(partData));
			
			// decrease iterations over bodyData while searching
			// for next delimeter from lastPartEndPos
			lastPartEndPos = nextDelimeterStartPos - 2;  
			
			// if we reached requestBody last delimeter (4 = 2 bytes for "--" and 2 bytes for "\r\n")
			// then get out of this loop and stop searching for next delimeter
			if (nextDelimeterStartPos + delimeterBytes.length + 4 == contentLength) break;
		}
		
		// for test purpose (see debug)
		for (Part part : bodyFactory.getParts()) {
			String partStr = new String(part.getPartData(), StandardCharsets.UTF_8);
		}
	}

	private void parseUrlEncodedBody() {
		byte[] requestBody = bodyFactory.getBodyData();
		RequestParameters requestParameters = headerFactory.getParameters();
		
		// parse parameters (email=admin@gmail.com&password=****)
		// and put them into RequestParameters
		String requestBodyStr = new String(requestBody);
		requestBodyStr = requestBodyStr.trim();
		String[] requestParamsStrArray = requestBodyStr.split("&");
		for (String requestParamStr : requestParamsStrArray) {
			String[] requestParamNameValueArr = requestParamStr.split("=");
			requestParameters.add(new RequestParameter(requestParamNameValueArr[0], requestParamNameValueArr[1]));
		}
		headerFactory.setParameters(requestParameters);
	}
	
	private String getHeaderFirstValue(String header) {
		int colon = header.indexOf(";");

		String firstValue = null;
		// in case if Content-Type: text/html; charset=utf-8 
		if (colon != -1) {
			firstValue = header.substring(0, colon);
		} else {
			firstValue = header;
		}
		return firstValue.toLowerCase();
	}
	
	private int getContentLength() {
		String contentLengthStr = getHeaderFirstValue(headerFactory.getHeader("Content-Length"));
		int contentLengthInt;
		try {
			contentLengthInt = Integer.parseInt(contentLengthStr);
		} catch (Exception e) {
			return 0;
		}
		return contentLengthInt;
	}
	
	private void addToRequestBuffer(byte[] requestPartBytes) {
		this.requestBuffer.put(requestPartBytes);
	}
	
	private boolean shouldExpectBody() {
		if (headerFactory.getMethod() == HttpMethod.POST) {
			return true;
		}
		return false;
	}
	
	private void checkContentLength() throws RequestTooBigException {
		if (contentLength > Server.getServerParameters().getMaxPostSize()) {
			throw new RequestTooBigException("Request body size exceeded limit!");
		}
	}
}
