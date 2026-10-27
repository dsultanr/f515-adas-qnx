package su.dsr.f515.qnx_adas;

import org.json.JSONArray;
import org.json.JSONObject;

/** Один кадр потока fcwd (JSON-строка, формат v1 — см. ~/f515/qnx-fcw/README.md). */
final class FcwdFrame {
    static final class Obj {
        int id, cls;
        float score, x1, y1, x2, y2;
    }

    // Плоскость дороги для кадра 1920x1080 — та же калибровка, что в fcwd (vp_fcw.h).
    static final float K = 1820f, Y_H = 540f, X_C = 989f, CAM_H = 1.51f;

    long seq;
    float t, speedPps, ttc, z, tExit, inferMs, fps;
    int state, lead, w = 1920, h = 1080;
    boolean suppressed;
    String warn = "";
    boolean vpCipo, vpCutIn, vpActive;
    float vpRawD, vpD, vpSd, vpVel, vpAccel;
    int vpLevel;
    Obj[] objs = new Obj[0];

    static FcwdFrame parse(String line) throws Exception {
        JSONObject j = new JSONObject(line);
        FcwdFrame f = new FcwdFrame();
        f.seq = j.optLong("seq");
        f.t = (float) j.optDouble("t");
        f.w = j.optInt("w", 1920);
        f.h = j.optInt("h", 1080);
        f.speedPps = (float) j.optDouble("speed", -1);
        f.state = j.optInt("state");
        f.warn = j.optString("warn");
        f.lead = j.optInt("lead", -1);
        f.ttc = (float) j.optDouble("ttc", -1);
        f.z = (float) j.optDouble("z");
        f.tExit = (float) j.optDouble("t_exit", -1);
        f.suppressed = j.optInt("sup") != 0;
        f.inferMs = (float) j.optDouble("infer_ms");
        f.fps = (float) j.optDouble("fps");
        JSONObject vp = j.optJSONObject("vp");
        if (vp != null) {
            f.vpCipo = vp.optInt("cipo") != 0;
            f.vpCutIn = vp.optInt("cut_in") != 0;
            f.vpRawD = (float) vp.optDouble("raw_d", -1);
            f.vpD = (float) vp.optDouble("d", -1);
            f.vpSd = (float) vp.optDouble("d_sd");
            f.vpVel = (float) vp.optDouble("vel");
            f.vpAccel = (float) vp.optDouble("accel");
            f.vpLevel = vp.optInt("lvl");
            f.vpActive = vp.optInt("active") != 0;
        }
        JSONArray a = j.optJSONArray("objs");
        if (a != null) {
            f.objs = new Obj[a.length()];
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONArray b = o.getJSONArray("b");
                Obj ob = new Obj();
                ob.id = o.optInt("id");
                ob.cls = o.optInt("c");
                ob.score = (float) o.optDouble("s");
                ob.x1 = (float) b.getDouble(0); ob.y1 = (float) b.getDouble(1);
                ob.x2 = (float) b.getDouble(2); ob.y2 = (float) b.getDouble(3);
                f.objs[i] = ob;
            }
        }
        return f;
    }

    /** Цель для схемы: ведущая по TTC, иначе ближайшая в своей полосе (класс 1). */
    Obj target() {
        Obj best = null;
        for (Obj o : objs) if (o.id == lead) return o;
        for (Obj o : objs) if (o.cls == 1 && (best == null || o.y2 > best.y2)) best = o;
        return best;
    }

    static float groundDistance(Obj o) {
        float dy = o.y2 - Y_H;
        return dy < 2 ? Float.NaN : K / dy;
    }

    static float lateral(Obj o) {
        float dy = o.y2 - Y_H;
        return dy < 2 ? 0 : CAM_H * ((o.x1 + o.x2) / 2 - X_C) / dy;
    }
}
