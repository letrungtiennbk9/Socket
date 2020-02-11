import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.HashMap;
import java.util.List;

public class RequestHandler implements Runnable {

	/**
	 * Socket connected to client passed by Proxy server
	 */
	Socket clientSocket;

	Socket serverSocKet;

	OutputStream serverOutputSteam;

	DataInputStream serverInputStream;

	DataInputStream clientInputStream;

	OutputStream clientOutputStream;

	HashMap<String, String> headerHM;

	protected String requestType, httpVersion, urlString, uri;
	
	/**
	 * Creates a ReuqestHandler object capable of servicing HTTP(S) GET requests
	 * @param clientSocket socket connected to the client
	 */
	public RequestHandler(Socket clientSocket){
		this.clientSocket = clientSocket;
	}

	/**
	 * Reads and examines the requestString and calls the appropriate method based 
	 * on the request type. 
	 */
	@Override
	public void run() {
		String requestString = null;
		try{
			clientInputStream = new DataInputStream(clientSocket.getInputStream());
			clientOutputStream = clientSocket.getOutputStream();
			
			String header = "";
			String line;
			String host = null;
			int CtL = 0;
			
			StringTokenizer tokens;

			headerHM = new HashMap<String, String>();
			if((line = clientInputStream.readLine()) !=null){
				if(line.length() == 0) return;
				if(requestString == null) requestString = line;
				header = header + line + "\n";
				tokens = new StringTokenizer(line);
				requestType = tokens.nextToken();
				urlString = tokens.nextToken();
				httpVersion = tokens.nextToken();
			}


			String key, value;


			while((line = clientInputStream.readLine()) != null) {
				// check for empty line
				if(line.trim().length() == 0) 
					break;
				header = header + line;
				// tokenize every header as key and value pair
				tokens = new StringTokenizer(line);
				key = tokens.nextToken(":");
				value = line.replaceAll(key, "").replace(": ", "");
				headerHM.put(key.toLowerCase(), value);
			}


			header += "\n";

			stripUnwantedHeaders();

			getUri();

			String request = requestString.substring(0,requestString.indexOf(' '));
		/* */
			
       /**/
			if(request.equals("POST")){
				System.out.println("Create new request for " + requestString);
				
				serverSocKet = new Socket(headerHM.get("host"), 80);
				serverOutputSteam = serverSocKet.getOutputStream();

				// check sv
				checkClientServerStream();

				postHandle();

				return;
			}


			if(request.equals("GET")){

			// System.out.println("Create new request for " + requestString);
			
			// Check if site is blocked
			String domainStr = new String();
			domainStr = headerHM.get("host");
			if(Proxy.isBlocked(domainStr)){
				System.out.println("Blocked site requested : " + urlString);
				blockedSiteRequested();
				return;
			}
			else{
				File f = Proxy.getCachedPage(urlString);
				if(f != null) {
					System.out.println("Cached file found for " + f.getName());
					sendCachedPageToClient(f);
				}
				else{
					System.out.println("Create new request for " + requestString);
					sendNonCachedToClient(urlString);
				}
			}
		
  

		}
		}catch(IOException e){ }
	}


	
	/**
	 * Sends the specified cached file to the client
	 * @param cachedFile The file to be sent (can be image/text)
	 */
	private void sendCachedPageToClient(File cachedFile){
		try{
			String contentType = cachedFile.getName().substring(cachedFile.getName().indexOf(" ") + 1);

			contentType = contentType.replace("__", "/");
			contentType = contentType.replace('_', '.');
			contentType = contentType.replace("?", "___");
			contentType = contentType.replace("____", "\\");
			contentType = contentType.replace("_____", ":");
			contentType = contentType.replace("______", "|");

			String response = 	"HTTP/1.0 200 OK\n" +
								"Content-Type: " + contentType + "\n" +
								"\n";
			OutputStream clientOutputStream = clientSocket.getOutputStream();
			clientOutputStream.write(response.getBytes());

			FileInputStream proxyInputStream = new FileInputStream(cachedFile);
			byte[] buffer = new byte[1024];
			int bytesRead = -1;
			while ((bytesRead = proxyInputStream.read(buffer)) != -1) {
				clientOutputStream.write(buffer, 0, bytesRead);
			}

			clientOutputStream.close();
			proxyInputStream.close();
		}
		catch(IOException e){}
	}


	/**
	 * Sends the contents of the file specified by the urlString to the client
	 * @param urlString URL ofthe file requested
	 */
	private void sendNonCachedToClient(String urlString){
		try{
			URL remoteURL = new URL(urlString);
			HttpURLConnection proxyToServerCon = (HttpURLConnection)remoteURL.openConnection();

			int responseCode = proxyToServerCon.getResponseCode();
			if(responseCode != HttpURLConnection.HTTP_OK){
				return;
			}

			// Get the initial file name
			String fileName = urlString.substring(7);
			String contentType = proxyToServerCon.getContentType();
			fileName = fileName + " " + contentType;

			// Remove any illegal characters from file name
			fileName = fileName.replace("/", "__");
			fileName = fileName.replace('.','_');
			fileName = fileName.replace("?","___");
			fileName = fileName.replace("\\","____");
			fileName = fileName.replace(":","_____");
			fileName = fileName.replace("|","______");			

			// Attempt to create File to cache to
			boolean caching = true;
			File fileToCache = null;
			FileOutputStream cachedFile_FOS = null;
			try{
				// Create File to cache 
				fileToCache = new File("cached/" + fileName);

				if(!fileToCache.exists()){
					fileToCache.createNewFile();
				}

				// Create Buffered output stream to write to cached copy of file
				cachedFile_FOS = new FileOutputStream(fileToCache);
			}
			catch (IOException e){
				System.out.println("Couldn't cache: " + fileName);
				caching = false;
			} catch (NullPointerException e) {
				System.out.println("NPE opening file");
			}

			InputStream remoteToClient_InputStream = proxyToServerCon.getInputStream();
			OutputStream clientOutputStream = clientSocket.getOutputStream();
			

			// String line =	"HTTP/1.0 200 OK" + "\n" + 
			// 				"Content-Type:" + proxyToServerCon.getHeaderField("Content-Type") + "\n" + 
			// 				"\n";
			// clientOutputStream.write(line.getBytes());
			String line = "HTTP/1.0 200 OK\n";
			Map<String, List<String>> m = proxyToServerCon.getHeaderFields();
			for(Map.Entry<String, List<String>> ent : m.entrySet()){
				if(ent.getKey() == null)	continue;

				if(ent.getKey().toLowerCase().startsWith("proxy")) continue;

				line += ent.getKey() + ": " + ent.getValue().get(0) + "\n";
			}
			line += "\n";
			clientOutputStream.write(line.getBytes());
			
			int bytesRead = -1;
            byte[] buffer = new byte[1024];
            while ((bytesRead = remoteToClient_InputStream.read(buffer)) != -1) {
				if(caching == true) cachedFile_FOS.write(buffer, 0, bytesRead);
				clientOutputStream.write(buffer,0,bytesRead);
            }
			
			if(caching) {
				if(!contentType.startsWith("text/html")){
					Proxy.addCachedPage(urlString, fileToCache);
				}
				cachedFile_FOS.close();
			}
			remoteToClient_InputStream.close();
			clientOutputStream.close();
			proxyToServerCon.disconnect();
		} 

		catch (Exception e){ }
	}

	private void checkClientServerStream()
	{
		try{
		// server
		if(serverSocKet.isOutputShutdown())
			serverOutputSteam = serverSocKet.getOutputStream();
		if(serverSocKet.isInputShutdown())	
			serverInputStream = new DataInputStream(serverSocKet.getInputStream());
		

		// client
		if(clientSocket.isOutputShutdown())
			clientOutputStream = clientSocket.getOutputStream();
		if(clientSocket.isInputShutdown())
			clientInputStream = new DataInputStream(clientSocket.getInputStream());
		}
		catch (IOException e) { return;} 
	}


	private void getUri(){
		if(headerHM.containsKey("host")) 
		{
			int temp = urlString.indexOf(headerHM.get("host"));
			temp += headerHM.get("host").length();

			if(temp < 0) { 
				// prevent index out of bound, use entire url instead
				uri = urlString;
			} else {
				// get uri from part of the url
				uri = urlString.substring(temp);	
			}
		}
	}


	private void stripUnwantedHeaders() {
		if(headerHM.containsKey("user-agent")) 
			headerHM.remove("user-agent");
		if(headerHM.containsKey("referer")) 
			headerHM.remove("referer");
		if(headerHM.containsKey("proxy-connection")) 
			headerHM.remove("proxy-connection");
		if(headerHM.containsKey("connection") && headerHM.get("connection").equalsIgnoreCase("keep-alive"))
			headerHM.remove("connection");

	}


	private void postHandle(){
		try{
			String requestStr = requestType + " " + uri + " HTTP/1.0";
			serverOutputSteam.write(requestStr.getBytes());
			serverOutputSteam.write("\r\n".getBytes());


			String command = "host: "+ headerHM.get("host");
			serverOutputSteam.write(command.getBytes());
			serverOutputSteam.write("\r\n".getBytes());

			// send rest of the headers
			for( String searchKey : headerHM.keySet()) {
				if(!searchKey.equals("host")){
					command = searchKey + ": "+ headerHM.get(searchKey);
					serverOutputSteam.write(command.getBytes());
					serverOutputSteam.write("\r\n".getBytes());
				}
			}

			serverOutputSteam.write("\r\n".getBytes());
			serverOutputSteam.flush();


			int contentLength = Integer.parseInt(headerHM.get("content-length"));
			for (int i = 0; i < contentLength; i++)
			{
				serverOutputSteam.write(clientInputStream.read());
			}		



			String tempLine ="";
			serverInputStream = new DataInputStream(serverSocKet.getInputStream());

			// get remote response header
			while((tempLine = serverInputStream.readLine()) != null) {

				// check for end of header blank line
				if(tempLine.trim().length() == 0) break;

				// check for proxy-connection: keep-alive
				if(tempLine.toLowerCase().startsWith("proxy")) continue;
				if(tempLine.contains("keep-alive")) continue;

				// write remote response to client
				clientOutputStream.write(tempLine.getBytes());
				clientOutputStream.write("\r\n".getBytes());
			}

			// complete remote header response
			clientOutputStream.write("\r\n".getBytes());
			serverOutputSteam.flush();


			InputStream remoteInputStream = serverSocKet.getInputStream();
			byte[] buffer = new byte[1024];

			for(int i; (i = remoteInputStream.read(buffer)) != -1;) {
				clientOutputStream.write(buffer, 0, i);
				clientOutputStream.flush();
			}
		}catch(Exception e)
		{}
	}
	/**
	 * This method is called when user requests a page that is blocked by the proxy.
	 * Sends an access forbidden message back to the client
	 */
	private void blockedSiteRequested(){
		try {
			// Viet Header va Status code
			OutputStream clientOS = clientSocket.getOutputStream();
			String response =	"HTTP/1.0 403 Forbidden\n"+
								"Content-Length: 232\n" +
								"Content-Type: text/html\n" + 
								"\n";
			clientOS.write(response.getBytes());

			// Mo file
			File forbiddenPageNotiFile = new File("forbiddenPageNoti.html");
			FileInputStream forbiddenPageNotiFIS = new FileInputStream(forbiddenPageNotiFile);

			// Bat dau doc file va ghi len socket
			byte[] buffer = new byte[1024];
			int bytesRead = -1;
			while((bytesRead = forbiddenPageNotiFIS.read(buffer)) != -1){
				clientOS.write(buffer, 0, bytesRead);
			}
			
			// Close file
			if(forbiddenPageNotiFIS != null){
				forbiddenPageNotiFIS.close();
			}			

		} catch (IOException e) {
			System.out.println("Error writing to client when requested a blocked site");
		}
	}
}