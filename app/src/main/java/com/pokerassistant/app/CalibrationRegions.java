package com.pokerassistant.app;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
public final class CalibrationRegions {
 private static final String PREFS="screen_calibration_v1";
 private CalibrationRegions(){}
 public static Rect get(Context c,String key,Rect fallback,int width,int height){
  SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
  if(!p.contains(key+"_x")) return new Rect(fallback);
  int l=Math.round(p.getFloat(key+"_x",0)*width), t=Math.round(p.getFloat(key+"_y",0)*height);
  Rect r=new Rect(l,t,l+Math.round(p.getFloat(key+"_w",0)*width),t+Math.round(p.getFloat(key+"_h",0)*height));
  return r.width()<20||r.height()<20?new Rect(fallback):r;
 }
 public static void put(Context c,String key,Rect r,int width,int height){
  c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
   .putFloat(key+"_x",r.left/(float)width).putFloat(key+"_y",r.top/(float)height)
   .putFloat(key+"_w",r.width()/(float)width).putFloat(key+"_h",r.height()/(float)height).apply();
 }
}