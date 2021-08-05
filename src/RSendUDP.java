import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.concurrent.locks.ReentrantLock;

import edu.utulsa.unet.RSendUDPI;
import edu.utulsa.unet.UDPSocket;
public class RSendUDP implements RSendUDPI {
	private InetAddress theirAddress;
	private int myPort = 13556;
	private int theirPort = 17886;
	PacketBuilder pB;
	UDPSocket sock;
	private int sws;
	private int packetWindowSize;
	private int mode = 0;
	private long windowSize = 256;
	private short waitSeqNum;
	private short currSeqNum;
	private String fileName;
	long timeout = 10; //in ms
	long sOTimeout = 100;
	DatagramPacket ackDP;
	FileInputStream file;
	int offset;
	int length;
	boolean oneMore = false;
	boolean finished;
	Long[] timeoutS;
	DatagramPacket[] packetWindow;
	int theirPacketSpace;
	boolean lastPacket = false;
	boolean end2 = false;
	private int lastPacketNum = 8;
	private int lastPacketTimer;
	private ReentrantLock lock = new ReentrantLock();
	private long startTime;
	
	
	public static void main(String[] args) {
		RSendUDP rS = new RSendUDP();
		rS.setMode(1);
		rS.setModeParameter(200);
		rS.setFilename("small_text_file.txt");
		rS.setLocalPort(13222);
		rS.setTimeout(100);
		try {
			rS.setReceiver( new InetSocketAddress(InetAddress.getLocalHost(), 18897));
		} catch (IOException e) {
			
		}
		rS.sendFile();
	}
	
	
	public RSendUDP() {
		try {
			theirAddress = InetAddress.getLocalHost();
			
		} catch (UnknownHostException e) {
			System.err.println(e);
		}
	}
	
	public boolean sendFile() {
		try {
			pB = new PacketBuilder(myPort, new InetSocketAddress(theirAddress, theirPort));
			sock = new UDPSocket(myPort);
			sock.setSoTimeout((int)sOTimeout);
			sws = sock.getSendBufferSize();
			if(mode == 0) {
				windowSize = sws;
			}
			if(windowSize < sws) {
				sws = (int)windowSize;
			}
			
			packetWindowSize = calcPacketWindowSize();
			
			file = new FileInputStream(fileName);
			theirPacketSpace = packetWindowSize;
			timeoutS = new Long[packetWindowSize];
			packetWindow = new DatagramPacket[packetWindowSize];
			System.out.println("Sending " + fileName + " from " + InetAddress.getLocalHost() + ":" + myPort + " to " + theirAddress + ":" + theirPort + " with " + Files.size(new File(fileName).toPath()) + " bytes");
			startTime = System.currentTimeMillis();
			if(mode == 0) {
				System.out.println("Using stop-and-wait");
				windowSize = sws;
				packetWindowSize = calcPacketWindowSize();
				theirPacketSpace = packetWindowSize;
				timeoutS = new Long[packetWindowSize];
				packetWindow = new DatagramPacket[packetWindowSize];
			} else {
				System.out.println("Using sliding window");
			}
			(new AckReciever()).start();
			while(!end2) {
				if(lock.isLocked())
					lock.unlock();
				
				lock.lock();
				if(posInWindow() + 1 <= packetWindowSize && theirPacketSpace > 0 && !lastPacket) {
					if(!sFile()) {
						lastPacket = true;
					} else {
						incCurrSeqNum();
					}
				}
				for(int i = 0; i < packetWindow.length ;i++) {
					DatagramPacket dataP = packetWindow[i];
					if(dataP != null) {
						if(System.currentTimeMillis() - timeoutS[i] >= timeout) {
							System.out.println("Resending packet (timeout), Sequence Number: " + PacketBuilder.getSeqNum(dataP));
							reSend(dataP);
							timeoutS[i] = System.currentTimeMillis();
						}
					}
				}
				if(lastPacket && isZero()) {
					if(lastPacketTimer > lastPacketNum) {
						System.out.println("Sending ten termination packets to reciever (in case a few are dropped)");
						end2 = true;
						lock.unlock();
						System.out.println("Successfully transferred " + fileName + " (" + Files.size(new File(fileName).toPath()) + " bytes) in " + ((System.currentTimeMillis() - startTime)/1000) + " seconds");
					}
					byte[] bytes = new byte[0];
					pB.setPayload(bytes);
					pB.setNumBytes((short)0);
					pB.setSeqNum(currSeqNum);
					reSend(new DatagramPacket(pB.getPacket().getData(), pB.getPacket().getLength(),pB.getPacket().getSocketAddress()));
					lastPacketTimer++;
				}
			}
			return true;
		} catch (IOException e) {
			System.err.println(e);
			return false;
		}
	}
	
	private int calcPacketWindowSize() throws SocketException {
		int rtn = (int)(windowSize/sws);
		if(rtn == 0) {
			sws = (int)windowSize;
			return 1;
		}
		return (int)(windowSize/sws);
	}

	private boolean isZero() {
		for(int i = 0; i < packetWindow.length; i++) {
			if(packetWindow[i] != null) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * calculate the number of bytes to the left starting from the highest seqNum
	 * 
	 * @return int # of bytes
	 * @throws SocketException
	 */
	private int calcBytesInWindow() throws SocketException {
		int size;
		if(waitSeqNum > currSeqNum) {
			size = (int)(windowSize - waitSeqNum);
			size += currSeqNum + 1;
		} else {
			size = currSeqNum - waitSeqNum;
		}
		
		return size * sock.getSendBufferSize();
	}
	
	
	
	/**
	 * position of the currSeqNum within the window
	 * @return 0 through packetWindowSize-1
	 */
	private int posInWindow() {
		short pos = 0;
		short seq = waitSeqNum;
		boolean found = false;
		while(!found) {
			if(seq == currSeqNum) {
				found = true;
			} else {
				pos++;
				seq++;
			}
		}
		return pos;
	}
	
	private int posInWindow(int recSeq) {
		int pos = 0;
		int seq = waitSeqNum;
		boolean found = false;
		while(!found) {
			if(seq == recSeq) {
				found = true;
			} else {
				pos++;
				seq++;
			}
		}
		return pos;
	}
	
	private void incCurrSeqNum() {
		currSeqNum++;
	}
	
	private void incWaitSeqNum() {
		waitSeqNum++;
		
	}
	
	private int seqDiff(int a, int b) {
		return posInWindow(a) - posInWindow(b);
	}
	
	@Override
	public String getFilename() {
		return fileName;
	}

	@Override
	public int getLocalPort() {
		return myPort;
	}

	@Override
	public int getMode() {
		return mode;
	}

	@Override
	public long getModeParameter() {
		return windowSize;
	}

	@Override
	public InetSocketAddress getReceiver() {
		return new InetSocketAddress(theirAddress, theirPort);
	}

	@Override
	public long getTimeout() {
		return timeout;
	}

	public boolean sFile() {
		try {
			byte[] bytes = new byte[sws - PacketBuilder.HEADER_SIZE];
			int num = file.read(bytes);
			if(num < sws - PacketBuilder.HEADER_SIZE) {
				lastPacket = true;
			}
			pB.setPayload(bytes);
			pB.setNumBytes((short)num);
			pB.setSeqNum(currSeqNum);
			sock.send(pB.getPacket());
			System.out.println("Sending packet, Sequence Number: " + currSeqNum);
			if(num > 0) {
				//System.out.println("Message " + currSeqNum + " sent with " + num + " bytes of actual data");
			} else {
				//System.out.println("Message " + currSeqNum + " sent with " + num + " bytes of actual data");
			}
			theirPacketSpace--;

			for(int i = 0; i < packetWindowSize; i++) {
				if(packetWindow[i] == null) {
					packetWindow[i] = new DatagramPacket(pB.getPacket().getData(),pB.getPacket().getLength(),pB.getPacket().getSocketAddress());
					timeoutS[i] = System.currentTimeMillis();
					break;
				}
			}

			if(num == -1) {
				return false;
			} else {
				return true;
			}
		} catch (IOException e) {
			System.out.println("Sender FAILURE");
			return false;
		}
	}
	
	public void reSend(DatagramPacket dP) {
		try {
			sock.send(dP);
			
		} catch (IOException e) {
			System.out.println("failed to resend");
			e.printStackTrace();
		}
	}

	@Override
	public void setFilename(String arg0) {
		fileName = arg0;
	}

	@Override
	public boolean setLocalPort(int arg0) {
		myPort = arg0;
		return true;
	}

	@Override
	public boolean setMode(int arg0) {
		mode = arg0;
		return true;
	}

	@Override
	public boolean setModeParameter(long arg0) {
		if(mode == 1) {
			windowSize = arg0;
			return true;
		}
		else
			return false;
	}

	@Override
	public boolean setReceiver(InetSocketAddress arg0) {
		theirAddress = arg0.getAddress();
		theirPort = arg0.getPort();
		return true;
	}

	@Override
	public boolean setTimeout(long arg0) {
		timeout = arg0;
		return true;
	}

	private void finish() {
		System.exit(0);
	}
	
	public class AckReciever extends Thread {
		
		public void run() {
			
			while(!end2) {
				try {
					ackDP = new DatagramPacket(new byte[sws], sws);
					sock.receive(ackDP);
					lock.lock();
					if(!end2) {
						System.out.println("SeqNum " + PacketBuilder.getSeqNum(ackDP) + " acknowledged");
					}
					
					theirPacketSpace = PacketBuilder.getRWSSize(ackDP);
					
					boolean change = false;
					
					for(int i = 0; i < packetWindow.length; i++) {
						DatagramPacket p = packetWindow[i];
						if(p != null) {
							if(PacketBuilder.getSeqNum(ackDP) == PacketBuilder.getSeqNum(p)) {
								packetWindow[i] = null;
								timeoutS[i] = null;
								if(PacketBuilder.getSeqNum(ackDP) == waitSeqNum) {
									incWaitSeqNum();
									change = false;
									int small = Integer.MAX_VALUE;
									for(int j = 0; j < packetWindow.length; j++) {
										if(posInWindow(currSeqNum) < packetWindow.length && packetWindow[j] != null) {
											if(posInWindow(PacketBuilder.getSeqNum(packetWindow[j])) < small) {
												change = true;
												small = posInWindow(PacketBuilder.getSeqNum(packetWindow[j]));
											}
										}
									}
									if(!change) {
										small = posInWindow(currSeqNum);
									}
									for(int j = 0; j < small; j++) {
										incWaitSeqNum();
									}
								}
								break;
							}
						}
					}
					boolean empty = true;
					for(DatagramPacket packet: packetWindow) {
						if(packet != null) {
							empty = false;
							break;
						}
					}
					lock.unlock();
				} catch (SocketTimeoutException e) {
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
