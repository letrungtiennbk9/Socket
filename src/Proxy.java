
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedReader;

public class Proxy implements Runnable{


	// Phương thức chính
	public static void main(String[] args) {
		// tạo một proxy và bắt đầu lắng nghe yêu cầu kết nối
		Proxy myProxy = new Proxy(8888);
		myProxy.listen();	
	}


	private ServerSocket serverSocket;

	/**
	 * Biến Kiểm tra xem proxy server có đang hoạt động hay không
	 */
	private volatile boolean running = true;


	/**
	 * Cấu trúc dữ liệu lưu tên URL kèm file cache
	 * Key: URL 
	 * Value: File
	 */
	static HashMap<String, File> cache;

	/**
	 * Cấu trúc dữ liệu lưu domain các trang web bị chặn
	 * Key: Domain
	 * Value: Domain
	 */
	static HashMap<String, String> blockedSites;

	/**
	 * Danh sách luồng đang chạy và thực hiện yêu cầu
	 * Danh sách này là cần thiết để có thể kiểm soát và dừng các luồng đang chạy khi chỉ thị dừng server
	 */
	static ArrayList<Thread> servicingThreads;



	/**
	 * Tạo một proxy server
	 * @param port: Port
	 */
	public Proxy(int port) {

		// Tạo 2 hash map lưu các trang cache và các domain bị chặn
		cache = new HashMap<>();
		blockedSites = new HashMap<>();

		// Tạo danh sách quản lý luồng
		servicingThreads = new ArrayList<>();

		// Bắt đầu luồng
		new Thread(this).start();

		try{
			// Load danh sách các trang đã cache từ file cachedSites.txt và lưu vào HashMap đã tạo.
			// Nếu proxy chưa cache vào chạy lần đầu sẽ tạo ra file cachedSites.txt để lưu HashMap những trang đã cache
			File cachedSites = new File("cachedSites.txt");
			if(!cachedSites.exists()){
				System.out.println("No cached sites found - creating new file");
				cachedSites.createNewFile();
			} else {
				FileInputStream fileInputStream = new FileInputStream(cachedSites);
				ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
				cache = (HashMap<String,File>)objectInputStream.readObject();
				fileInputStream.close();
				objectInputStream.close();
			}
			
			// Tương tự như load danh sách HashMap, ở đây sẽ làm cho trang bị chặn
			File blockedSitesConfFile = new File("blacklist.conf");
			if(!blockedSitesConfFile.exists()){
				System.out.println("No blocked sites found - creating new file");
				blockedSitesConfFile.createNewFile();
			} else {
				BufferedReader br = new BufferedReader(new FileReader("blacklist.conf"));
				    String line;
				    while ((line = br.readLine()) != null) {
				    	if(!blockedSites.containsKey(line)) {
				    		blockedSites.put(line, line);
				    	}
				    }
				
			}
		} catch (IOException e) {
			System.out.println("Error loading previously cached sites file");
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			System.out.println("Class not found loading in preivously cached sites file");
			e.printStackTrace();
		}

		try {
			// Tạo server socket và đặt lệnh khởi tạo để proxy bắt đầu chạy
			serverSocket = new ServerSocket(port);

			System.out.println("Waiting for client on port " + serverSocket.getLocalPort() + "..");
			running = true;
		} 

		// Catch exceptions
		catch (SocketException se) {
			System.out.println("Socket Exception when connecting to client");
			se.printStackTrace();
		}
		catch (SocketTimeoutException ste) {
			System.out.println("Timeout occured while connecting to client");
		} 
		catch (IOException io) {
			System.out.println("IO exception when connecting to client");
		}
	}


	/**
	 * Lắng nghe client ở port và chấp nhận các yêu cầu kết nối
	 * Tạo luồng để xử lý yêu cầu và thêm luồng quản lý
	 */
	public void listen(){

		while(running){
			try {
				// Chấp nhận yêu cầu kết nối
				Socket socket = serverSocket.accept();
				
				// Tạo luồng mới với yêu cầu vừa chấp nhận
				Thread thread = new Thread(new RequestHandler(socket));
				
				// Thêm vào luồng vào danh sách quản lý luồng
				servicingThreads.add(thread);

				// Bắt đầu chạy luồng
				thread.start();	
			} catch (SocketException e) {
				// Socket exception khi dừng proxy
				System.out.println("Server closed");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}


	/**
	 * Lưu các trang cache và domain bị chặn xuống file
	 * Truy cập và chặn tất cả các luồng còn đang chạy
	 */
	private void closeServer(){
		System.out.println("\nClosing Server..");
		running = false;
		try{
			FileOutputStream fileOutputStream = new FileOutputStream("cachedSites.txt");
			ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);

			objectOutputStream.writeObject(cache);
			objectOutputStream.close();
			fileOutputStream.close();
			System.out.println("Cached Sites written");

			FileWriter writer = new FileWriter("blacklist.conf", false);
			for(String key : blockedSites.keySet()) {
                String value = blockedSites.get(key);
                if(value != null) {
                    writer.write(key.trim());
                    writer.write("\r\n");
                }
            }                    
            writer.close();

			System.out.println("Blocked Site list saved");
			try{
				// Chờ các luồng đang xử lý dừng
				for(Thread thread : servicingThreads){
					if(thread.isAlive()){
						System.out.print("Waiting on "+  thread.getId()+" to close..");
						thread.join();
						System.out.println(" closed");
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			} catch (IOException e) {
				System.out.println("Error saving cache/blocked sites");
				e.printStackTrace();
			}

			// Đóng server socket
			try{
				System.out.println("Terminating Connection");
				serverSocket.close();
			} catch (Exception e) {
				System.out.println("Exception closing proxy's server socket");
				e.printStackTrace();
			}

		}


		/**
		 * Tìm file trong cache
		 * @param url: URL
		 * @return File: File
		 */
		public static File getCachedPage(String url){
			return cache.get(url);
		}


		/**
		 * Thêm trang vào cache
		 * @param urlString: URL
		 * @param fileToCache: File
		 */
		public static void addCachedPage(String urlString, File fileToCache){
			cache.put(urlString, fileToCache);
		}

		/**
		 * Kiểm tra URL có bị proxy chặn
		 * @param url:URL
		 * @return true nếu URL bị chặn, false nếu không bị chặn
		 */
		public static boolean isBlocked (String domain){
			if(blockedSites.get(domain) != null){
				return true;
			} else {
				return false;
			}
		}



		/**
		 * Tạo giao diện cập nhật cấu hình proxy server
		 * 		blocked		: In danh sách domain bị chặn
		 *  	close		: Đóng proxy server
		 *  	Domain/URL	: Thêm Domain vào danh sách chặn
		 * 		delete		: Xóa tất cả những file đã cache trước đó
		 *  	unblock Domain/URL	: Bỏ chặn Domain
		 */
		@Override
		public void run() {
			Scanner scanner = new Scanner(System.in);

			String command;
			String _Check_unblock = "unblock";
			String _Check_url = "http://";
			while(running){
				System.out.println("Enter new site to block, or type \"blocked\" to see blocked sites or \"close\" to close server or \"delete\" to delete all caches, or \"unblock + domain/url\" to unblock.");
				command = scanner.nextLine();
				if(command.toLowerCase().equals("blocked")){
					System.out.println("\nCurrently Blocked Sites");
					for(String key : blockedSites.keySet()){
						System.out.println(key);
					}
					System.out.println();
				} 

				else if(command.equals("close")){
					running = false;
					closeServer();
				}
				// Kiểm tra nếu lệnh ở console là xóa cache ...
				else if(command.equals("delete")){
					File cachedsite = new File("cachedSites.txt");
					cache.clear();
					cachedsite.delete();
					File file = new File("cached/");
					File[] listOfFile = file.listFiles();
					if(listOfFile.length > 0){
						for (int i = 0; i < listOfFile.length; i++) {
							System.out.println("File : " + listOfFile[i].getName());
							listOfFile[i].delete();
							System.out.println("Has been deleted successfully!!");
						}
					}
					else{
						System.out.println("Cached folder is empty.");
					}
				}
				// Kiểm tra nếu lệnh ở console là unblock ...
				else if (command.length() > 7) {
					if(command.substring(0,7).equals("unblock")) {
						String _Domain = command.substring(8);
						if(_Domain.length() > 7) {
							if(_Domain.substring(0,7).equals("http://")) {
								_Domain = _Domain.substring(7);
								int index = _Domain.indexOf("/");
								try{
								_Domain = _Domain.substring(0,index);
								}catch(IndexOutOfBoundsException e)
								{}
							}
						}
						if(blockedSites.containsKey(_Domain)) {
							blockedSites.remove(_Domain);
							System.out.println("\n" + _Domain + " unblocked successfully \n");
						}
						else
							System.out.println("\n" + _Domain + " is not blocked\n");
					}
					else {				// trường hợp block url
						String _Domain = command;
						if(command.substring(0,7).equals("http://")) {
							_Domain = _Domain.substring(7);
							int index = _Domain.indexOf("/");
							try{
							_Domain = _Domain.substring(0,index);	
							}catch(IndexOutOfBoundsException e)
							{}						
						}

						if(!blockedSites.containsKey(_Domain)) {
							blockedSites.put(_Domain,_Domain);
							System.out.println("\n" + _Domain + " blocked successfully \n");
						}						
						else
						{
							System.out.println("\n" + _Domain + " is blocked before  \n");
						}
					}
						
				}
				else {					// trường hợp block domain
					if(!blockedSites.containsKey(command)) {
						blockedSites.put(command,command);
						System.out.println("\n" + command + " blocked successfully \n");
					}	
					else
					{
						System.out.println("\n" + command + " is blocked before  \n");
					}
					
				}
			}
				
			scanner.close();
		} 

	}
