package com.pokerassistant.app;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
public final class CalibrationActivity extends Activity {
 private static final int REQ=8127;
 @Override public void onCreate(Bundle b){
  super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(7,12,22));getWindow().setNavigationBarColor(Color.rgb(7,12,22));
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(28,42,28,28);root.setBackgroundColor(Color.rgb(7,12,22));
  TextView title=new TextView(this);title.setText("SCREEN CALIBRATION / PRO");title.setTextColor(Color.rgb(65,230,255));title.setTextSize(23);title.setLetterSpacing(.07f);root.addView(title);
  TextView info=new TextView(this);info.setText("Настрой области прямо поверх ClubGG. Перетаскивай прямоугольник, чтобы переместить его; тяни за яркий нижний правый угол, чтобы изменить размер. Кнопками снизу переключай левую карту, правую карту и общие карты. Нажми СОХРАНИТЬ — координаты сохранятся для следующих запусков.");info.setTextColor(Color.rgb(223,232,246));info.setTextSize(16);info.setPadding(0,24,0,28);root.addView(info);
  Button start=new Button(this);start.setText("ЗАПУСТИТЬ КАЛИБРОВКУ ПОВЕРХ ИГРЫ");start.setAllCaps(false);start.setTextColor(Color.BLACK);start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(65,230,255)));root.addView(start,new LinearLayout.LayoutParams(-1,60));
  start.setOnClickListener(v->{if(!Settings.canDrawOverlays(this))startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),REQ);else launch();});
  setContentView(root);
 }
 @Override protected void onActivityResult(int q,int r,Intent d){super.onActivityResult(q,r,d);if(q==REQ&&Settings.canDrawOverlays(this))launch();}
 private void launch(){startService(new Intent(this,CalibrationOverlayService.class));finish();}
}