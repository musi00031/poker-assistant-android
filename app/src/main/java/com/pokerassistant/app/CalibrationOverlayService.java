package com.pokerassistant.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/** Full-screen calibration layer: drag a region to move it and its bottom-right handle to resize. */
public final class CalibrationOverlayService extends Service {
    private WindowManager wm;
    private CalibrationView view;
    private int sw, sh, active;
    private final Rect[] regions = new Rect[3];
    private final int[] colors = {Color.rgb(35,235,255), Color.rgb(255,65,190), Color.rgb(160,255,65)};
    private final String[] keys = {"hero_left", "hero_right", "board"};
    private final String[] names = {"ЛЕВАЯ КАРТА", "ПРАВАЯ КАРТА", "ОБЩИЕ КАРТЫ"};

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        regions[0] = CalibrationRegions.get(this, keys[0],
                new Rect(sw/24, (int)(sh*.70), (int)(sw*.15), (int)(sh*.79)), sw, sh);
        regions[1] = CalibrationRegions.get(this, keys[1],
                new Rect((int)(sw*.13), (int)(sh*.70), (int)(sw*.24), (int)(sh*.79)), sw, sh);
        regions[2] = CalibrationRegions.get(this, keys[2],
                new Rect((int)(sw*.08), (int)(sh*.27), (int)(sw*.92), (int)(sh*.67)), sw, sh);

        view = new CalibrationView(this);
        // Avoid NO_LIMITS: on some Android skins it offsets the view canvas from touch coordinates.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(view, lp);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_NOT_STICKY; }
    @Override public void onDestroy() {
        if (view != null) try { wm.removeView(view); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }

    private final class CalibrationView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float downX, downY;
        Rect original;
        boolean resizing;

        CalibrationView(Context c) { super(c); setLayerType(View.LAYER_TYPE_SOFTWARE, null); setClickable(true); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x8805101c);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            for (int i=0; i<3; i++) {
                Rect r=regions[i];
                p.setColor(colors[i]); p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(i==active ? 3 : 1.5f)); c.drawRect(r,p);
                p.setStyle(Paint.Style.FILL); p.setAlpha(i==active ? 55 : 18); c.drawRect(r,p); p.setAlpha(255);
                p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(dp(12)); p.setColor(colors[i]);
                c.drawText(names[i], r.left+dp(5), Math.max(dp(95),r.top-dp(7)), p);
                if (i==active) {
                    float h=dp(38);
                    p.setColor(colors[i]); c.drawRoundRect(r.right-h,r.bottom-h,r.right,r.bottom,dp(5),dp(5),p);
                    p.setColor(Color.BLACK); p.setTextSize(dp(20)); c.drawText("↘",r.right-h+dp(6),r.bottom-dp(7),p);
                }
            }
            p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); p.setTextSize(dp(13));
            c.drawText("ПЕРЕТАСКИВАЙ ОБЛАСТЬ • УГОЛ ↘ = РАЗМЕР",dp(10),dp(28),p);
            c.drawText("Активно: "+names[active],dp(10),dp(49),p);

            float gap=dp(5), bw=(getWidth()-gap*4)/3f, by=getHeight()-dp(62);
            String[] labels={"ЛЕВАЯ","ПРАВАЯ","БОРД"};
            for(int i=0;i<3;i++){
                float x=gap+i*(bw+gap);
                p.setColor(colors[i]); c.drawRoundRect(x,by,x+bw,by+dp(38),dp(7),dp(7),p);
                p.setColor(Color.BLACK); p.setTextSize(dp(11)); c.drawText(labels[i],x+dp(7),by+dp(24),p);
            }
            p.setColor(Color.rgb(90,235,255));
            c.drawRoundRect(dp(10),getHeight()-dp(112),getWidth()-dp(10),getHeight()-dp(70),dp(8),dp(8),p);
            p.setColor(Color.BLACK); p.setTextSize(dp(15)); c.drawText("СОХРАНИТЬ ОБЛАСТИ",dp(24),getHeight()-dp(84),p);
            p.setColor(Color.WHITE); p.setTextSize(dp(12)); c.drawText("× ЗАКРЫТЬ БЕЗ СОХРАНЕНИЯ",dp(15),getHeight()-dp(15),p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x=e.getX(), y=e.getY();
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN) {
                float gap=dp(5), bw=(getWidth()-gap*4)/3f, by=getHeight()-dp(62);
                // Hit-test the three visible selector buttons using their exact drawn bounds.
                if(y>=by && y<=by+dp(40)) {
                    for(int i=0;i<3;i++) {
                        float left=gap+i*(bw+gap);
                        if(x>=left && x<=left+bw) { active=i; invalidate(); return true; }
                    }
                    return true;
                }
                if(y>=getHeight()-dp(112) && y<=getHeight()-dp(70)) {
                    for(int i=0;i<3;i++) CalibrationRegions.put(CalibrationOverlayService.this,keys[i],regions[i],sw,sh);
                    Toast.makeText(CalibrationOverlayService.this,"Области сохранены",Toast.LENGTH_LONG).show();
                    stopSelf();
                    return true;
                }
                if(y>=getHeight()-dp(28)) { stopSelf(); return true; }

                Rect r=regions[active];
                downX=x; downY=y; original=new Rect(r);
                resizing=x>=r.right-dp(48) && x<=r.right+dp(8)
                        && y>=r.bottom-dp(48) && y<=r.bottom+dp(8);
                return true;
            }
            if(action==MotionEvent.ACTION_MOVE && original!=null) {
                Rect r=regions[active];
                int dx=Math.round(x-downX), dy=Math.round(y-downY);
                int maxW=Math.max(1,Math.min(sw,getWidth()));
                int maxH=Math.max(1,Math.min(sh,getHeight()));
                if(resizing) {
                    r.right=Math.max(r.left+dp(50),Math.min(maxW,original.right+dx));
                    r.bottom=Math.max(r.top+dp(50),Math.min(maxH,original.bottom+dy));
                } else {
                    int w=original.width(), h=original.height();
                    int l=Math.max(0,Math.min(maxW-w,original.left+dx));
                    int t=Math.max(dp(55),Math.min(maxH-h,original.top+dy));
                    r.set(l,t,l+w,t+h);
                }
                invalidate();
                return true;
            }
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                original=null;
                return true;
            }
            return true;
        }
    }
}