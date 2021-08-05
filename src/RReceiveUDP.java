import edu.utulsa.unet.RReceiveUDPI;
import edu.utulsa.unet.UDPSocket;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
public class RReceiveUDP implements RReceiveUDPI {
	private InetAddress theirAddress;
	private int myPort = 12445;
	private int theirPort = 19444;
	
	PacketBuilder pB;
	UDPSocket sock;
	
	private int sws;
	private int mode = 0;				
	private long windowSize = 256;
	private short waitSeqNum;
	private short currSeqNum;
	private short highSeqNum;
	private String fileName = "text2.txt";
	long timeout = 100; 
	FileInputStream file;
	int offset;
	int length;
	boolean end = false;
	boolean oneMore = false;
	boolean finished;
	DatagramPacket recDP;
	private int packetWindowSize;
	DatagramPacket[] packetWindow;
	private File rtnFile;
	private FileOutputStream outStr;

	public static void main(String[] args) {
		RReceiveUDP rR = new RReceiveUDP();
		rR.setMode(1);
		rR.setModeParameter(600);
		rR.setFilename("text2.txt");
		rR.setLocalPort(18897);
		rR.receiveFile();
	}
	
	public RReceiveUDP() {
		try {
			theirAddress = InetAddress.getLocalHost();
		} catch ( UnknownHostException  e) {
			e.printStackTrace();
		}
	}
	
	public boolean receiveFile() {
		try {
			pB = new PacketBuilder(myPort, new InetSocketAddress(theirAddress, theirPort));
			sock = new UDPSocket(myPort);
			sws = sock.getSendBufferSize();
			if(mode == 0) {
				windowSize = sws;
			}
			packetWindowSize = calcPacketWindowSize();
			packetWindow = new DatagramPacket[packetWindowSize];
			waitSeqNum = 0;
			currSeqNum = 0;
			highSeqNum = (short)(packetWindowSize - 1);
			
			if(windowSize < sws) {
				sws = (int)windowSize;
			}
			
			rtnFile = new File(fileName);
			outStr = new FileOutputStream(rtnFile);
			file = new FileInputStream(fileName);
			if(mode == 0) {
				windowSize = sws;
				packetWindowSize = calcPacketWindowSize();
				packetWindow = new DatagramPacket[packetWindowSize];
				highSeqNum = (short)(packetWindowSize - 1);
			}
			boolean end = false;
		} catch (FileNotFoundException | SocketException  e) {
			e.printStackTrace();
		}
		
		
		while(!end) {
			if(rFile()) {
				end = true;
			}
		}
		return true;
	}
	
	private int calcPacketWindowSize() throws SocketException {
		return (int)(windowSize/sws);
		
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
	
	private void incCurrSeqNum() {
		currSeqNum++;
	}
	
	private void incWaitSeqNum() {
		waitSeqNum++;
	} 
	
	private void incHighSeqNum() {
		highSeqNum++;
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

	private boolean isNotInWindow(short num1) {
		
		int seq = waitSeqNum;
		boolean found = false;
		while(!found) {
			if(num1 == seq) {
				found = true;
			} else if (seq == highSeqNum) {
				return true;
			} else {
				seq++;
			}
		}
		return false;
	}
	
	private int seqDiff() {
		return posInWindow(currSeqNum) - posInWindow(waitSeqNum);
	}
	
	private int seqDiff(int a, int b) {
		return posInWindow(a) - posInWindow(b);
	}

	
	public boolean rFile() {
		try {
			recDP = new DatagramPacket(new byte[sws], sws);
			sock.receive(recDP);
			if(PacketBuilder.getNumBytes(recDP) <= 0) {
				return true;
			}
			//System.out.println("Packet Recieved from " + theirAddress.toString() + ":" + theirPort + ". Sequence Number: " + PacketBuilder.getSeqNum(recDP));
			if(!isNotInWindow(PacketBuilder.getSeqNum(recDP))) {
				pB.setDestinationSocketAddress(PacketBuilder.readSenderAddress(recDP));
				theirAddress = PacketBuilder.readSenderAddress(recDP).getAddress();
				theirPort = PacketBuilder.readSenderAddress(recDP).getPort();
				System.out.println("Recieved packet that is within the recieving window --> ACKing, Sequence Number: " + PacketBuilder.getSeqNum(recDP));
				sendACK(PacketBuilder.getSeqNum(recDP));
				
				boolean change = true;
				for(DatagramPacket packet: packetWindow) {
					if(packet != null) {
						if(PacketBuilder.getSeqNum(packet) == PacketBuilder.getSeqNum(recDP)) {
							change = false;
						}
					}
				}
				if(change) {
					if(PacketBuilder.getSeqNum(recDP) == waitSeqNum) {
						System.out.println("Writing packet to file, Sequence Number: " + PacketBuilder.getSeqNum(recDP));
						outStr.write(PacketBuilder.readPayload(recDP), 0, PacketBuilder.getNumBytes(recDP));
						incWaitSeqNum();
						incCurrSeqNum();
						incHighSeqNum();
						
						while(change) {
							change = false;
							for(int i = 0; i< packetWindow.length; i++) {
								
								if(packetWindow[i] != null) {
									if(waitSeqNum == PacketBuilder.getSeqNum(packetWindow[i])) {
										System.out.println("Writing packet to file, Sequence Number: " + PacketBuilder.getSeqNum(packetWindow[i]));
										outStr.write(PacketBuilder.readPayload(packetWindow[i]),0, PacketBuilder.getNumBytes(packetWindow[i]));
										packetWindow[i] = null;
										incWaitSeqNum();
										incHighSeqNum();
										change = true;
										break;
									}
								}
							}
						}
					} else {
						for(int i = 0; i < packetWindowSize; i++) {
							if(packetWindow[i] == null) {
								packetWindow[i] = new DatagramPacket(recDP.getData(),recDP.getLength(),recDP.getSocketAddress());
								break;
							}
						}
						if(seqDiff(PacketBuilder.getSeqNum(recDP),currSeqNum) > 0) {
							currSeqNum = PacketBuilder.getSeqNum(recDP);
						}
					}
				}
			} else {
				pB.setDestinationSocketAddress(PacketBuilder.readSenderAddress(recDP));
				System.out.println("Recieved packet that was not in recieving window --> ACKing,    Sequence Number: " + PacketBuilder.getSeqNum(recDP));
				sendACK(PacketBuilder.getSeqNum(recDP));
			}
			return false;
		} catch (IOException e) {
			System.out.println("Receive file failure");
			e.printStackTrace();
			return false;
		}
	}
	
	private void sendACK(short ackSeqNum) {
		short value = 0;
		int val = currSeqNum;
		while(!(val == highSeqNum + 1)) {
			val++;
			value++;
		}
		pB.setRWSSize(value);
		pB.setPayload(new byte[0]);
		pB.setSeqNum(ackSeqNum);
		try {
			sock.send(pB.getPacket());
			//System.out.println("Sending ACK, Sequence Number: " + ackSeqNum);
		} catch (IOException e) {
			System.out.println("Failed to send ACK");
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
	
	private boolean isZero(byte[] array) {
		for(int i = 0; i < array.length; i++) {
			if(array[i] != 0) {
				return false;
			}
		}
		return true;
	}
	
	private void finish() {
		System.out.println("DONE!");
		System.exit(0);
	}
	
}

