package su.dsr.f515.qnx_adas;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Capture from real AIS, inspect a complete archive and test export permissions. */
public final class CalibrationDeviceTest extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle b) { super.onCreate(b); args=b; start(); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void status(String text) { Bundle b=new Bundle();b.putString("stream",text+"\n");sendStatus(0,b); }
    @Override public void onStart() {
        Bundle result=new Bundle(); boolean ok=false;
        try {
            Context c=getTargetContext();QnxControl q=new QnxControl(c);
            boolean running=q.running(); String before=processes(q);
            boolean fallback=args!=null && "true".equals(args.getString("fallback"));
            File archive=CalibrationArchive.capture(c,fallback);
            status("CAPTURE_ARCHIVE="+archive.getAbsolutePath());
            check(running==q.running(),"Capture changed recognition state");
            check(before.equals(processes(q)),"Capture changed ADAS process IDs");
            try (ZipFile zip=new ZipFile(archive)) {
                check(zip.size()==5,"Incomplete archive");
                check(zip.getEntry("frame.uyvy").getSize()==1920L*1080*2,"Raw frame size");
                JSONObject metadata;
                try(InputStream in=zip.getInputStream(zip.getEntry("metadata.json"))) {
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
                    while((n=in.read(b))!=-1)out.write(b,0,n);
                    metadata=new JSONObject(out.toString("UTF-8"));
                }
                check(metadata.getInt("width")==1920 && metadata.getInt("height")==1080,"Frame dimensions");
                check(metadata.getInt("crop_top")==120 && metadata.getInt("model_width")==1024,"Preprocessing metadata");
                check(zip.getEntry("input.ppm").getSize()==1024L*512*3+16,"Model input size");
            }
            Bitmap preview=CalibrationArchive.preview(archive);
            check(preview!=null && preview.getWidth()==480 && preview.getHeight()==270,"Preview decode");preview.recycle();
            Uri uri=new Uri.Builder().scheme("content").authority(c.getPackageName()+".calibration").appendPath(archive.getName()).build();
            try(InputStream in=c.getContentResolver().openInputStream(uri)) {check(in.read()=='P' && in.read()=='K',"Shared ZIP");}
            boolean denied=false;
            try(android.os.ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"w")) { }
            catch(FileNotFoundException expected){denied=true;}
            check(denied,"Provider allowed writes");
            status("PASS calibration capture, SHA-256 transfer, PNG/PPM/metadata and read-only sharing; ADAS unchanged; fallback="+fallback);
            ok=true;result.putString("stream","PASS ALL calibration checks\n");
        }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));}
        finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private static String processes(QnxControl q)throws Exception {
        String text=q.qnx("pidin -p fcwd -f an 2>/dev/null",2);StringBuilder out=new StringBuilder();
        for(String line:text.split("\n"))if(line.trim().matches("[0-9]+\\s+/*dev/shmem/fcwd"))out.append(line.trim()).append('\n');
        return out.toString();
    }
}
