
import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

public class Server {

    public static void main(String[] args){

        while(true) {
            try {
                int port = Integer.parseInt(args[0]);
                String file = args[1];
                DatagramSocket listen = new DatagramSocket(port);
                serverLoop(listen, file);
                listen.close();
            } catch (UnsupportedAudioFileException | IOException e) {
                e.printStackTrace();
            }
        }

    }

    private static void serverLoop(DatagramSocket socket, String file) throws IOException, UnsupportedAudioFileException {

        byte[] buf = new byte[256];

        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);

        InetAddress address = packet.getAddress();
        int port = packet.getPort();

        File source = new File(file);

        AudioInputStream stream = AudioSystem.getAudioInputStream(source);
        AudioFormat format = stream.getFormat();

        //send format over to client so they can stream correctly
        byte[] formatDetails = format.toString().getBytes();
        packet = new DatagramPacket(formatDetails , formatDetails.length, address, port);
        socket.send(packet);

        //loop to send all packets
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(source));
        int packetSize = 2048;
        double fileSize = Math.ceil(((int) source.length()) /packetSize);
        int waitTime = 200;
        double i = 0;
        ArrayList<DatagramPacket> resend = new ArrayList<>();

        while(i <= fileSize) {

            byte[] msg = new byte[packetSize];
            bis.read(msg, 16, msg.length - 16);
            resend.add(makeData(i, msg, address, port));
            i++;
        }
        i = 0;

        while(i < resend.size()) {
            System.out.println(i);
            socket.send(resend.get((int) i));
            i = recurseResend(socket,packetSize, i); //need a way to ignore multiple resend issues
            i++;
        }


        int ended = 0;

        while(ended < 5) {
            byte[] end = ("end").getBytes();
            packet = new DatagramPacket(end, end.length, address, port);
            socket.send(packet);
            try {
                socket.setSoTimeout(100);
                socket.receive(packet);
                ended = 6;
            }catch(SocketTimeoutException e){
                ended++;
            }

        }
    }

    private static DatagramPacket makeData(double i , byte[] msg,InetAddress address, int port) {
        //add a sequence number to the message
        byte[] seqNum = new byte[8];
        ByteBuffer.wrap(seqNum).putDouble(i);
        System.arraycopy(seqNum, 0, msg, 0, 8);

        Date now = new Date();
        byte[] nowArray = new byte[8];
        ByteBuffer.wrap(nowArray).putLong(now.getTime());

        System.arraycopy(nowArray, 0, msg, 8, 8);

        DatagramPacket packet = new DatagramPacket(msg, msg.length, address, port);
        return packet;
    }


    private static double recurseResend(DatagramSocket socket, int packetSize, double current) throws IOException {
        byte[] buf = new byte[packetSize];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
            socket.setSoTimeout(3);
            socket.receive(packet);

            String ret = new String(packet.getData(), 0, packet.getLength());

            if(ret.contains("resend")){
                String[] set = ret.split(",");
                socket.setSoTimeout(0);
                double x = Double.parseDouble(set[1]);
                return x;
            }
        }
        catch(SocketTimeoutException e){
            socket.setSoTimeout(0);
        }
        return current;
    }
}


