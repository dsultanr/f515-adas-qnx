package su.dsr.f515.qnx_adas;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.concurrent.*;

/** Real TCP against a synthetic ADB peer on an ephemeral loopback port. Never connects to adbd. */
public final class AccessTransportTest {
    static final int CNXN=0x4e584e43,OPEN=0x4e45504f,OKAY=0x59414b4f,CLSE=0x45534c43,WRTE=0x45545257;
    static int checks;
    static byte[] key;
    interface Peer{void run(Socket socket)throws Exception;}
    interface Client{void run(AdbClient adb)throws Exception;}
    static void check(boolean result){checks++;if(!result)throw new AssertionError("check "+checks);}
    static int[] receive(InputStream input)throws Exception{
        DataInputStream in=new DataInputStream(input);byte[] header=new byte[24];in.readFully(header);
        ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);int[] p=new int[6];for(int i=0;i<6;i++)p[i]=b.getInt();
        byte[] bytes=new byte[p[3]];in.readFully(bytes);return p;
    }
    static void send(OutputStream out,int command,int arg0,int arg1,byte[] bytes,boolean corrupt)throws Exception{
        int sum=0;for(byte b:bytes)sum+=b&255;
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(command).putInt(arg0).putInt(arg1).putInt(bytes.length).putInt(sum+(corrupt?1:0)).putInt(command^0xffffffff).array());out.write(bytes);out.flush();
    }
    static void scenario(int timeout,Peer peer,Client test)throws Exception{
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            FutureTask<Void> serving=new FutureTask<>(()->{
                try(Socket s=listener.accept()){
                    s.setSoTimeout(2000);receive(s.getInputStream());send(s.getOutputStream(),CNXN,0x01000000,262144,new byte[0],false);peer.run(s);
                }return null;
            });
            Thread t=new Thread(serving,"fake-adb-peer");t.setDaemon(true);t.start();
            try(AdbClient adb=new AdbClient("127.0.0.1",listener.getLocalPort(),key,new byte[]{0},timeout)){
                test.run(adb);
            }
            serving.get(3,TimeUnit.SECONDS);
        }
    }
    static void fails(AdbClient client)throws Exception{boolean failed=false;try{client.shell("test");}catch(IOException expected){failed=true;}check(failed);}
    public static void main(String[] args)throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);key=java.util.Base64.getEncoder().encode(g.generateKeyPair().getPrivate().getEncoded());
        scenario(1500,s->{int[] p=receive(s.getInputStream());check(p[0]==OPEN);send(s.getOutputStream(),OKAY,7,p[1],new byte[0],false);send(s.getOutputStream(),WRTE,7,p[1],"0\n".getBytes(StandardCharsets.UTF_8),false);receive(s.getInputStream());send(s.getOutputStream(),CLSE,7,p[1],new byte[0],false);},a->check(a.shell("id -u").equals("0\n")));
        scenario(2000,s->{
            int[] first=receive(s.getInputStream());check(first[0]==OPEN);
            send(s.getOutputStream(),OKAY,7,first[1],new byte[0],false);
            send(s.getOutputStream(),WRTE,7,first[1],"first".getBytes(StandardCharsets.UTF_8),false);check(receive(s.getInputStream())[0]==OKAY);
            send(s.getOutputStream(),CLSE,7,first[1],new byte[0],false);
            int[] second=receive(s.getInputStream());check(second[0]==OPEN);check(second[1]!=first[1]);
            send(s.getOutputStream(),CLSE,7,first[1],new byte[0],false);
            send(s.getOutputStream(),OKAY,7,first[1],new byte[0],false);
            send(s.getOutputStream(),WRTE,7,first[1],"stale".getBytes(StandardCharsets.UTF_8),false);
            send(s.getOutputStream(),OKAY,9,second[1],new byte[0],false);
            send(s.getOutputStream(),WRTE,9,second[1],"second".getBytes(StandardCharsets.UTF_8),false);check(receive(s.getInputStream())[0]==OKAY);
            send(s.getOutputStream(),CLSE,0,second[1],new byte[0],false);
        },a->{check(a.shell("one").equals("first"));check(a.shell("two").equals("second"));});
        scenario(1500,s->{int[] p=receive(s.getInputStream());send(s.getOutputStream(),OKAY,7,p[1],new byte[0],false);send(s.getOutputStream(),WRTE,7,p[1],new byte[]{1},true);},AccessTransportTest::fails);
        scenario(1500,s->{int[] p=receive(s.getInputStream());send(s.getOutputStream(),OKAY,7,p[1],new byte[0],false);send(s.getOutputStream(),WRTE,7,p[1],"partial".getBytes(StandardCharsets.UTF_8),false);receive(s.getInputStream());},AccessTransportTest::fails);
        scenario(300,s->{receive(s.getInputStream());while(s.getInputStream().read()!=-1){}},a->{long start=System.nanoTime();fails(a);check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);});
        scenario(1500,s->{int[] p=receive(s.getInputStream());send(s.getOutputStream(),OKAY,7,p[1],new byte[0],false);s.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(WRTE).putInt(7).putInt(p[1]).putInt(262145).putInt(0).putInt(WRTE^0xffffffff).array());s.getOutputStream().flush();},AccessTransportTest::fails);
        scenario(1500,s->{receive(s.getInputStream());while(s.getInputStream().read()!=-1){}},a->{
            FutureTask<Boolean> read=new FutureTask<>(()->{try{a.shell("wait");return false;}catch(IOException expected){return true;}});Thread t=new Thread(read);t.start();Thread.sleep(30);long start=System.nanoTime();a.close();check(read.get(500,TimeUnit.MILLISECONDS));check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);
        });
        scenario(1500,s->{
            int[] p=receive(s.getInputStream());
            send(s.getOutputStream(),OKAY,7,p[1],new byte[0],false);
            send(s.getOutputStream(),WRTE,7,p[1],"echo DONE\nprompt> \nDO".getBytes(StandardCharsets.UTF_8),false);
            check(receive(s.getInputStream())[0]==OKAY);
            send(s.getOutputStream(),WRTE,7,p[1],"NE\n".getBytes(StandardCharsets.UTF_8),false);
            check(receive(s.getInputStream())[0]==OKAY);check(receive(s.getInputStream())[0]==CLSE);
        },a->check(a.shellUntil("upload", "DONE").endsWith("\nDONE\n")));
        scenario(2000,s->{
            int[] p=receive(s.getInputStream());send(s.getOutputStream(),OKAY,7,p[1],new byte[0],false);
            byte[] echo=new byte[65536];java.util.Arrays.fill(echo,(byte)'A');
            for(int i=0;i<20;i++){send(s.getOutputStream(),WRTE,7,p[1],echo,false);receive(s.getInputStream());}
            send(s.getOutputStream(),WRTE,7,p[1],"\nDONE\n".getBytes(StandardCharsets.UTF_8),false);
            receive(s.getInputStream());check(receive(s.getInputStream())[0]==CLSE);
        },a->{String tail=a.shellUntil("upload", "DONE");check(tail.length()<65536);check(tail.endsWith("\nDONE\n"));});
        System.out.println("PASS local access transport: "+checks+" checks; synthetic loopback peer only");
    }
}
