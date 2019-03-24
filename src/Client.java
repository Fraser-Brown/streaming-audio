import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;


public class Client{

    public static void main(String[] args) throws LineUnavailableException, IOException {

        try {
            Thread.sleep(100);

            byte[] buf;
            int port = Integer.parseInt(args[1]);
            int packetSize = 2048;
            InetAddress address = InetAddress.getByName(args[0]);
            DatagramSocket socket = new DatagramSocket();
            DatagramPacket packet;

            //sends confirm to the server
            buf = ("hello").getBytes();
            packet = new DatagramPacket(buf, buf.length, address, port);
            socket.send(packet);
            socket.setSoTimeout(0);

            //set up a file to write streamed data into
            String out = "test.wav";

            if(args.length == 3) {
                out = args[2];
            }

            FileOutputStream fos = new FileOutputStream(out);
            BufferedOutputStream bos = new BufferedOutputStream(fos);

            //format details relevant to streaming playback

            buf = new byte[packetSize];
            packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);

            String formatDetails = new String(packet.getData(), 0, packet.getLength()).trim();

            //Splits up all the format data for usage
            String[] forD = formatDetails.split(", ");
            String[] encodingAndSample = forD[0].split(" ");
            AudioFormat.Encoding encoding;
            switch (encodingAndSample[0]){
                case "PCM_SIGNED":
                    encoding = AudioFormat.Encoding.PCM_SIGNED;
                    break;
                case "ALAW":
                    encoding = AudioFormat.Encoding.ALAW;
                    break;
                case "PCM_FLOAT":
                    encoding = AudioFormat.Encoding.PCM_FLOAT;
                    break;
                case "PCM_UNSIGNED":
                    encoding = AudioFormat.Encoding.PCM_UNSIGNED;
                    break;
                case "ULAW":
                    encoding = AudioFormat.Encoding.ULAW;
                    break;
                default:
                    encoding = null;
                    break;
            }

            float sample = Float.parseFloat(encodingAndSample[1]);
            int sampleSize = Integer.parseInt(forD[1].split(" ")[0]);
            int channel;
            if(forD[2].equals("mono")){
                channel = 1;
            }
            else{
                channel = 2;
            }
            int bytesPerFrame = Integer.parseInt(forD[3].split(" ")[0]);
            boolean endian;
            endian = !forD[4].equals("little-endian");

            socket.setSoTimeout(0);

            //sourcedataline is used as a buffer to play audio
            AudioFormat format = new AudioFormat(encoding, sample, sampleSize, channel, bytesPerFrame, sample, endian);
            SourceDataLine music = AudioSystem.getSourceDataLine(format);
            music.open(format,500000);

            //loops over receiving packets
            streamingLoop(buf, packetSize,port, address, packet, socket, bos, music);

            bos.close();

        } catch (SocketTimeoutException e) {

            byte[] buf;
            int port = Integer.parseInt(args[1]);
            InetAddress address = InetAddress.getByName(args[0]);
            DatagramSocket socket = new DatagramSocket();
            DatagramPacket packet;
            buf = ("hello").getBytes();
            packet = new DatagramPacket(buf, buf.length, address, port);
            socket.send(packet);

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    private static void streamingLoop(byte[] buf, int packetSize, int port, InetAddress address, DatagramPacket packet, DatagramSocket socket, BufferedOutputStream bos, SourceDataLine music) throws IOException {
        boolean running = true;
        int stripOffset = 16;
        ArrayList<packetPairs> reordered = new ArrayList<>();
        double last = 0;
        int writeBack = 20;
        ArrayList<Double> alreadyResent = new ArrayList<>();
        boolean notGottenZero = true;
        double resendThreshold = 100;

            while (running) {

                buf = new byte[packetSize];
                packet = new DatagramPacket(buf, buf.length);

                socket.receive(packet);
                String received = new String(packet.getData(), 0, packet.getLength());
                received = received.trim();

                if (received.equals("end")) {
                    running = false;
                    System.out.println("closed, finishing");
                    sendmsg(socket,address,port, "finished");
                }

                byte[] seqNum = packet.getData();
                double seq = ByteBuffer.wrap(seqNum, 0, 8).getDouble();
                byte[] timeSent = packet.getData();
                long time = ByteBuffer.wrap(timeSent, 8, 8).getLong();

                if(notGottenZero && seq > 0){
                    String msg = "resend," + (last-1);
                    sendmsg(socket, address, port, msg);
                }

                 else if (seq == last + 1 || last == 0) {

                    if(seq == 0){
                        notGottenZero = false;
                    }
                    packetPairs x = new packetPairs(seq, packet);
                    reordered.add(x);

                    if (reordered.size() > writeBack) {

                        if(music.available() < 0.1 * music.getBufferSize()) {
                            music.start();
                        }
                        if(music.available() > 0.9 * music.getBufferSize()) {
                            music.stop();
                        }

                        reordered.sort(Comparator.comparing(packetPairs -> packetPairs.getSeq()));
                        writeMusic(reordered, bos, stripOffset, music, socket, address, port);
                        reordered = new ArrayList<>();

                    }
                    last = seq;
                }

                else {
                    if((!alreadyResent.contains(last)) || seq > last + resendThreshold) {
                        alreadyResent.add(last);
                        String msg = "resend," + (last);
                        sendmsg(socket, address, port, msg);
                    }
                }

            }

    }



    private static void sendmsg(DatagramSocket socket, InetAddress address, int port, String msg) throws IOException {
        byte[] buffer = msg.getBytes();
        socket.send(new DatagramPacket(buffer, buffer.length, address, port));
    }

    private static void writeMusic(ArrayList<packetPairs> packets, BufferedOutputStream bos, int stripOffset, SourceDataLine music, DatagramSocket socket, InetAddress address, int port) throws IOException {

        ArrayList<Double> removeRepeats = new ArrayList<>();

        double last = -1;

        for (packetPairs x: packets) {

            if(!removeRepeats.contains(x.getSeq())) {
                removeRepeats.add(x.getSeq());
                DatagramPacket packet = x.getPacket();
                double seq = x.getSeq();

                if(last == -1){
                    last = seq;
                }

                else if(seq - 1 != last){  //to ensure that music is played at correct speed
                    byte[] filler = new byte[2048];
                    bos.write(filler, stripOffset, filler.length - stripOffset);
                    //write music into streaming buffer
                    music.write(filler, stripOffset, filler.length - stripOffset);
                }

                byte[] recievedAudio = packet.getData();
                //write music to file
                bos.write(recievedAudio, stripOffset, recievedAudio.length - stripOffset);
                //write music into streaming buffer
                music.write(recievedAudio, stripOffset, recievedAudio.length - stripOffset);

                //If the correct sequence number was recieved it is echoed back to confirm
                byte[] req = new byte[8];
                ByteBuffer.wrap(req).putDouble(seq);
            }
            last = x.getSeq();
        }
    }

}

class packetPairs {
    double seq;
    DatagramPacket packet;

    public packetPairs(double seq, DatagramPacket packet) {
        this.seq = seq;
        this.packet = packet;
    }

    public double getSeq() {
        return seq;
    }

    public DatagramPacket getPacket() {
        return packet;
    }
}
