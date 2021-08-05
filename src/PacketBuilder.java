import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.DatagramPacket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

/**
 * Class to manage DatagramPackets and data format
 */
public class PacketBuilder
{
	public static void main(String[] args) {
		test();
	}

	public static void test() {
		try {
			System.out.println("Testing PacketBuilder with LocalHost sending \"Hello World\" message from port 32156 with short fields 15 and 512.");
			PacketBuilder pb = new PacketBuilder(32156, new InetSocketAddress(InetAddress.getLocalHost(),32156));
			pb.setShortField((short)15, 1);
			pb.setShortField((short)512, 2);
			pb.setPayload("Hello World".getBytes());
			DatagramPacket dp = pb.getPacket();
			byte[] data = dp.getData();
			System.out.println("Local Host: " + InetAddress.getLocalHost());
			System.out.println("Packet size: " + data.length);
			System.out.print("Packet data:");
			for(int idx = 0; idx < data.length; idx++) {
				System.out.print(" " + data[idx]);
			}
			System.out.println("\nPacket sender address: " + PacketBuilder.readSenderAddress(dp).getAddress());
			System.out.println("Packet sender port: " + PacketBuilder.readSenderAddress(dp).getPort());
			System.out.println("Packet short fields: " + PacketBuilder.readShortField(dp, 1) + ", " + PacketBuilder.readShortField(dp, 2));
		} catch (UnknownHostException err) {
			err.printStackTrace();
		}
	}

	// 4 for IP, 2 for sender port, 2 for sequence number, 2 for data length / RWS
	public static final int HEADER_SIZE = 4 + 2 + 2 + 2;
	private byte[] header, payload;
	private DatagramPacket packet;
	
	/**
	 * Create a new PacketBuilder object with a set port for receiving ACKs.
	 * Assumes that IP for receiving ACKs is same as the sender.
	 *
	 * @param port the port of the sending node
	 */
	public PacketBuilder(int localPort) {
		header = new byte[HEADER_SIZE];
		setSenderInfo(localPort);
		payload = new byte[0];
		packet = new DatagramPacket(new byte[0], 0);
	}
	
	/**
	 * Create a new PacketBuilder object with a set port for receiving ACKs.
	 * Assumes that IP for receiving ACKs is same as the sender.
	 *
	 * @param port the port of the sending node
	 * @param receiver the InetSocketAddress of the receiving node
	 */
	public PacketBuilder(int localPort, InetSocketAddress receiver) {
		header = new byte[HEADER_SIZE];
		setSenderInfo(localPort);
		payload = new byte[0];
		packet = new DatagramPacket(new byte[0], 0, receiver);
	}

	/**
	 * Sets the InetSocketAddress to send the packet to
	 * 
	 * @param receiver the InetSocketAddress of the receiver
	 */
	public void setDestinationSocketAddress(InetSocketAddress receiver) {
		packet.setSocketAddress(receiver); }
	
	/**
	 * Sets the byte array of data to be sent.
	 *
	 * @param data the byte array to be set as the payload
	 */
	public void setPayload(byte[] data) { 
		payload = data; }

	/**
	 * Sets one of the short fields for the packet.
	 *
	 * @param fieldNum which field to set (1 or 2)
	 * @param value the value to set the field to
	 */
	public void setShortField(short value, int fieldNum) {
		byte[] shortBytes = ByteBuffer.allocate(2).putShort(value).array();
		if(fieldNum == 1) { fieldNum--; }
		header[6 + fieldNum] = shortBytes[0];
		header[7 + fieldNum] = shortBytes[1];
	}

	public void setSeqNum(short value) {
		setShortField(value, 1);
	}
	
	public void setNumBytes(short value) {
		setShortField(value, 2);
	}
	
	public void setRWSSize(short value) {
		setShortField(value, 2);
	}
	
	/**
	 * Returns a DatagramPacket with all currently stored information.
	 * Does NOT reset the currently stored information. Consecutive calls
	 * will return a packet with the exact same information.
	 *
	 * @return a DatagramPacket with all information currently stored in the PacketBuilder object
	 */
	public DatagramPacket getPacket() {
		buildPacket();
		return packet; }

	/**
	 * Reads the sender's InetSocketAddress from a DatagramPacket built using this class.
	 *
	 * @param pckt the packet to read the sender's address from
	 * @return InetSocketAddress of the packet's sender
	 */
	public static InetSocketAddress readSenderAddress(DatagramPacket pckt) {
		// initialize variables
		byte[] data = pckt.getData();
		byte[] addr = {0, 0, 0, 0};
		byte[] port = {0, 0, 0, 0};
		int portNum;
		// get the InetAddress
		for(int idx = 0; idx < 4; idx++) {
			addr[idx] = data[idx]; }
		// get the port number
		for(int idx = 4; idx < 6; idx++) {
			port[idx-2] = data[idx]; }
		portNum = ByteBuffer.wrap(port).getInt();
		// return the InetSocketAddress
		try {
			return new InetSocketAddress(InetAddress.getByAddress(addr), portNum);
		} catch (UnknownHostException e) { e.printStackTrace(); }
		return null;
	}
	
	/**
	 * Reads the payload from a DatagramPacket built using this class.
	 *
	 * @param pckt the packet to read the payload of
	 * @return byte array of the packet's payload
	 */
	public static byte[] readPayload(DatagramPacket pckt) {
		byte[] data = pckt.getData();
		byte[] ret = new byte[data.length-HEADER_SIZE];
		for(int idx = HEADER_SIZE; idx < data.length; idx++) {
			ret[idx-HEADER_SIZE] = data[idx]; }
		return ret;
	}

	/**
	 * Reads the sequence number from a DatagramPacket built using this class.
	 *
	 * @param pckt the packet to read the short from
	 * @param fieldNum which short to read (1 or 2)
	 * @return the short stored in the header
	 */
	public static short readShortField(DatagramPacket pckt, int fieldNum) {
		byte[] toConvert = {0, 0};
		if(fieldNum == 1) { fieldNum--; }
		toConvert[0] = pckt.getData()[6 + fieldNum];
		toConvert[1] = pckt.getData()[7 + fieldNum];
		return ByteBuffer.wrap(toConvert).getShort();
	}
	
	public static short getSeqNum(DatagramPacket pckt) {
		return readShortField(pckt, 1);
	}
	
	public static short getNumBytes(DatagramPacket pckt) {
		return readShortField(pckt, 2);
	}
	
	public static short getRWSSize(DatagramPacket pckt) {
		return readShortField(pckt, 2);
	}

	/**
	 * Builds the DatagramPacket
	 */
	private void buildPacket() {
		byte[] toSend = new byte[HEADER_SIZE + payload.length];
		for(int idx = 0; idx < HEADER_SIZE; idx++) {
			toSend[idx] = header[idx]; }
		for(int idx = 0; idx < payload.length; idx++) {
			toSend[idx+HEADER_SIZE] = payload[idx]; }
		packet.setData(toSend, 0, toSend.length);
	}

	/**
	 * Sets the byte array of sender information
	 */
	private void setSenderInfo(int port) {
		try {
			byte[] sAddr = InetAddress.getLocalHost().getAddress();
			for(int idx = 0; idx < 4; idx++) {
				header[idx] = sAddr[idx]; }
		} catch (UnknownHostException err) {
			err.printStackTrace(); }
		byte[] sPort = ByteBuffer.allocate(4).putInt(port).array();
		header[4] = sPort[2];
		header[5] = sPort[3];
	}
}
