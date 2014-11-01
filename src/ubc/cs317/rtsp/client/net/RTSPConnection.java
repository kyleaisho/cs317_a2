/*
 * University of British Columbia
 * Department of Computer Science
 * CPSC317 - Internet Programming
 * Assignment 2
 * 
 * Author: Jonatan Schroeder
 * January 2013
 * 
 * This code may not be used without written consent of the authors, except for 
 * current and future projects and assignments of the CPSC317 course at UBC.
 */

package ubc.cs317.rtsp.client.net;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Date;
import java.util.Arrays;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;

import ubc.cs317.rtsp.client.exception.RTSPException;
import ubc.cs317.rtsp.client.model.Frame;
import ubc.cs317.rtsp.client.model.Session;

/**
 * This class represents a connection with an RTSP server.
 */
public class RTSPConnection {

	private static final int BUFFER_LENGTH = 15000;
	private static final long MINIMUM_DELAY_READ_PACKETS_MS = 20;

	// Header is always 12 bytes
	private static final int HEADER_LENGTH = 12;

	private Session session;
	private Timer rtpTimer;
	private Socket socket;
	BufferedReader RTSPBufferedReader;
	BufferedWriter RTSPBufferedWriter;
	private static String video;
	private DatagramSocket datagramSocket;
	static int datagramPort = 26650;
	int CSeqNum = 0;
	String RTSP_V = "RTSP/1.0";
	String SPACE = "\r\n";
	int state;
	final static int PLAYING = 2;
	final static int READY = 1;
	final static int INITALIZE = 0;
	int sessID = 0;
	final static int SET = 200;
	final static int TIMEOUT = 1000;
	static long startingTime = 0;

	// Fields for Part B Statistics
	static int totalPackets = 0;
	static int totalPacketsLost = 0;
	static int totalPacketsOutOfOrder = 0;
	long startTime;
	long endTime;


	/**
	 * Establishes a new connection with an RTSP server. No message is sent at
	 * this point, and no stream is set up.
	 * 
	 * @param session
	 *            The Session object to be used for connectivity with the UI.
	 * @param server
	 *            The hostname or IP address of the server.
	 * @param port
	 *            The TCP port number where the server is listening to.
	 * @throws RTSPException
	 *             If the connection couldn't be accepted, such as if the host
	 *             name or port number are invalid or there is no connectivity.
	 */
	public RTSPConnection(Session session, String server, int port)
			throws RTSPException {

		this.session = session;

		try {
			socket = new Socket(server, port);
			state = INITALIZE;


		}
		catch (Exception e) {
			e.printStackTrace();
			throw new RTSPException(e);
		}

	}
	/**
	 * Sends a SETUP request to the server. This method is responsible for
	 * sending the SETUP request, receiving the response and retrieving the
	 * session identification to be used in future messages. It is also
	 * responsible for establishing an RTP datagram socket to be used for data
	 * transmission by the server. The datagram socket should be created with a
	 * random UDP port number, and the port number used in that connection has
	 * to be sent to the RTSP server for setup. This datagram socket should also
	 * be defined to timeout after 1 second if no packet is received.
	 * 
	 * @param videoName
	 *            The name of the video to be setup.
	 * @throws RTSPException
	 *             If there was an error sending or receiving the RTSP data, or
	 *             if the RTP socket could not be created, or if the server did
	 *             not return a successful response.
	 */
	public synchronized void setup(String videoName) throws RTSPException {
		RTSPConnection.video = videoName;
		try {
			RTSPBufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
			CSeqNum++;
			sendRequest("SETUP");
			if (readResponse() != SET) {
				System.out.println("Invalid Response");
			}
			else {
				state = READY;
			}

		}
		catch (IOException e) {
			e.printStackTrace();
			throw new RTSPException(e);
		}

		try{
			datagramSocket = new DatagramSocket(datagramPort);
			datagramSocket.setSoTimeout(TIMEOUT);
		} catch (SocketException s){
			s.printStackTrace();
		}
	}

	private void sendRequest(String string) throws IOException {
		String request =string + " " + video + " " + RTSP_V + SPACE;
		RTSPBufferedWriter.write(request);
		System.out.print(request);
		String Cseq = "CSeq: " + CSeqNum + SPACE;
		RTSPBufferedWriter.write(Cseq);
		System.out.print(Cseq);
		if(state == INITALIZE){
			String transport = "TRANSPORT: RTP/UDP; client_port= " + datagramPort + SPACE + SPACE;
			RTSPBufferedWriter.write(transport);
			System.out.print(transport);
		}
		else {
			String session = "Session: " + sessID + SPACE + SPACE;
			RTSPBufferedWriter.write(session);
			System.out.println(session);
		}
		RTSPBufferedWriter.flush();
	}

	private int readResponse() {
		int code = 0;
		String response;
		try {
			response = RTSPBufferedReader.readLine();
			System.out.println(response);
			code = readCode(response);
			if(code == 200){
				response = RTSPBufferedReader.readLine();
				System.out.println(response);
				response = RTSPBufferedReader.readLine();
				System.out.println(response);
				if(sessID == 0){
					setID(response);
				}


			}
		}
		catch (IOException e) {

		}
		return code;
	}

	private int readCode(String response) {
		int code;
		StringTokenizer token = new StringTokenizer(response);
		token.nextToken();
		code = Integer.valueOf(token.nextToken());
		return code;
	}

	private void setID(String response) {
		StringTokenizer session = new StringTokenizer(response);
		session.nextToken();
		sessID = Integer.valueOf(session.nextToken());
	}
	/**
	 * Sends a PLAY request to the server. This method is responsible for
	 * sending the request, receiving the response and, in case of a successful
	 * response, starting the RTP timer responsible for receiving RTP packets
	 * with frames.
	 * 
	 * @throws RTSPException
	 *             If there was an error sending or receiving the RTSP data, or
	 *             if the server did not return a successful response. 
	 */
	public synchronized void play() throws RTSPException {

		if(state == READY){

			startTime = System.currentTimeMillis();
			CSeqNum++;
			try {
				RTSPBufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
				sendRequest("PLAY");
			} catch (IOException e) {

				e.printStackTrace();
				throw new RTSPException(e);
			}
			if(readResponse()!= SET){
				System.out.println("Invalid Response");
			}
			else{
				state = PLAYING;
				startRTPTimer();
			}

		}


	}

	/**
	 * Starts a timer that reads RTP packets repeatedly. The timer will wait at
	 * least MINIMUM_DELAY_READ_PACKETS_MS after receiving a packet to read the
	 * next one.
	 */
	private void startRTPTimer() {

		rtpTimer = new Timer();
		rtpTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				receiveRTPPacket();
			}
		}, 0, MINIMUM_DELAY_READ_PACKETS_MS);
	}

	/**
	 * Receives a single RTP packet and processes the corresponding frame. The
	 * data received from the datagram socket is assumed to be no larger than
	 * BUFFER_LENGTH bytes. This data is then parsed into a Frame object (using
	 * the parseRTPPacket method) and the method session.processReceivedFrame is
	 * called with the resulting packet. In case of timeout no exception should
	 * be thrown and no frame should be processed.
	 * 
	 */
	private void receiveRTPPacket() {

		// Create a byte array to store the incoming packet
		byte [] rtpBuff = new byte [BUFFER_LENGTH];

		// Create a DatagramPacket for the socket to receive
		DatagramPacket rtpPacket = new DatagramPacket(rtpBuff, BUFFER_LENGTH);

		try {
			datagramSocket.receive(rtpPacket);
			Frame f = parseRTPPacket(rtpPacket.getData(), BUFFER_LENGTH);
			session.processReceivedFrame(f);

		}
		catch (IOException e) {
			System.out.println(e.getMessage());
			// no frame should be processed

		}

	}

	/**
	 * Sends a PAUSE request to the server. This method is responsible for
	 * sending the request, receiving the response and, in case of a successful
	 * response, cancelling the RTP timer responsible for receiving RTP packets
	 * with frames.
	 * 
	 * @throws RTSPException
	 *             If there was an error sending or receiving the RTSP data, or
	 *             if the server did not return a successful response.
	 */
	public synchronized void pause() throws RTSPException {
		if (state == PLAYING){
			CSeqNum++;
			try{
				RTSPBufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
				sendRequest("PAUSE");
			} catch (IOException e) {
				e.printStackTrace();
				throw new RTSPException(e);
			}
			if(readResponse() != SET){
				System.out.println("Invalid Response");
			}
			else{
				state = READY;
				rtpTimer.cancel();
			}
		}
	}

	/**
	 * Sends a TEARDOWN request to the server. This method is responsible for
	 * sending the request, receiving the response and, in case of a successful
	 * response, closing the RTP socket. This method does not close the RTSP
	 * connection, and a further SETUP in the same connection should be
	 * accepted. Also this method can be called both for a paused and for a
	 * playing stream, so the timer responsible for receiving RTP packets will
	 * also be cancelled.
	 * 
	 * @throws RTSPException
	 *             If there was an error sending or receiving the RTSP data, or
	 *             if the server did not return a successful response.
	 */
	public synchronized void teardown() throws RTSPException {
		if (state == PLAYING || state == READY){
			CSeqNum++;
			try {
				RTSPBufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
				sendRequest("TEARDOWN");
				if(readResponse() != SET){
					throw new RTSPException("Invalid Response");
				}
				else{
					rtpTimer.cancel();
					state = INITALIZE;
					datagramSocket.close();
					sessID = 0;
					endTime = System.currentTimeMillis();
					getStats();
				}
			} catch (IOException e) {
				e.printStackTrace();
				throw new RTSPException(e);
			}
		}

	}

	/**
	 * Closes the connection with the RTSP server. This method should also close
	 * any open resource associated to this connection, such as the RTP
	 * connection, if it is still open.
	 */
	public synchronized void closeConnection() {
		try{
			socket.close();
			datagramSocket.close();
			RTSPBufferedReader.close();
			RTSPBufferedWriter.close();
		} catch (IOException e){
			System.out.println("Im in the exception"); //If we delete this we get stuck in a loop when we disconnect WTF?
			e.printStackTrace();
		}
	}

	/**
	 * Parses an RTP packet into a Frame object.
	 * 
	 * @param packet
	 *            the byte representation of a frame, corresponding to the RTP
	 *            packet.
	 * @return A Frame object.
	 */
	private static Frame parseRTPPacket(byte[] packet, int length) {

		Frame f = null;
		byte [] header = new byte[HEADER_LENGTH]; 
		byte [] payload = new byte[packet.length - header.length]; 

		header = Arrays.copyOfRange(packet, 0, HEADER_LENGTH - 1);
		payload = Arrays.copyOfRange(packet, HEADER_LENGTH, packet.length - 1);

		// get the marker, payload type, sequence number and timestamp from the header
		boolean mark = ((header[1] >> 7) == 0x1);
		byte pt = (byte) ((header[1] & 0xff) & 0x7f);
		short sn = (short) ((header[2] << 8) | (header[3] & 0xff));
		int ts = (((header[4] & 0xff) << 24) 
				| ((header[5] & 0xff) << 16)
				| ((header[6] & 0xff) << 8) 
				| (header[7] & 0xff));
		f = new Frame(pt, mark, sn, ts, payload, 0, payload.length - 1);

		// Adding function to calculate statistics for Part A
		updatePacketNums();

		return f; 
	}


	private static void updatePacketNums() {
		totalPackets++;
		//totalPacketsLost++;
		//totalPacketsOutOfOrder++;
	}
	
	private void getStats() {
		long runTime = (endTime - startTime)/1000;
		System.out.println("Total packets/sec: " + totalPackets / runTime);
		System.out.println("Total packets lost");
		System.out.println();
	}

}























