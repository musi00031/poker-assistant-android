package com.pokerassistant.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual card verification screen. User labels the visible cards explicitly;
 * labels are saved locally and used as a manual override until auto mode is restored.
 */
public final class ManualLearningActivity extends Activity {
    private final int BG = Color.rgb(8, 9, 12);
    private final int PANEL = Color.rgb(21, 22, 27);
    private final int SILVER = Color.rgb(205, 211, 224);
    private final int ACCENT = Color.rgb(65, 230, 255);
    private final String[] ranks = {"2","3","4","5","6","7","8","9","10","J","Q","K","A"};
    private final String[] suits = {"s","h","d","c"};
    private final String[] symbols = {"♠","♥","♦","♣"};
    private final PokerEngine.Card[] selected = new PokerEngine.Card[7];
    private final Button[] slots = new Button[7];
    private TextView status;

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        SharedPreferences p = getSharedPreferences("manual_learning_v1", MODE_PRIVATE);
        for (int i=0;i<7;i++) {
            String code = p.getString("card_"+i, "");
            if (!code.isEmpty()) try { selected[i] = PokerEngine.Card.parse(code); } catch (Exception ignored) {}
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(22),dp(18),dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("РУЧНОЕ ИЗУЧЕНИЕ / ПРОВЕРКА");
        title.setTextColor(ACCENT); title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Если автоматика путает масти, вручную укажи карты, которые сейчас действительно лежат на столе. Сохранённая разметка применяется к текущему раздаваемому набору и помогает проверить расчёт. Это ручное подтверждение, а не переобучение ONNX-модели. Не оставляй старую разметку для следующей раздачи.");
        info.setTextColor(SILVER); info.setTextSize(14);
        info.setPadding(0,dp(12),0,dp(18));
        root.addView(info);

        addHeading(root,"ТВОИ КАРТЫ");
        addSlot(root,0,"Левая карта");
        addSlot(root,1,"Правая карта");
        addHeading(root,"ОБЩИЕ КАРТЫ — выбирай только те, что уже открыты");
        for (int i=2;i<7;i++) addSlot(root,i,"Борд "+(i-1));

        status = new TextView(this);
        status.setTextColor(SILVER); status.setTextSize(13);
        status.setPadding(0,dp(14),0,dp(12));
        root.addView(status);
        refresh();

        Button save = button("СОХРАНИТЬ РУЧНУЮ РАЗМЕТКУ");
        root.addView(save, params());
        save.setOnClickListener(v -> saveLabels());

        Button clear = button("ОЧИСТИТЬ И ВЕРНУТЬ АВТОРЕЖИМ");
        root.addView(clear, params());
        clear.setOnClickListener(v -> {
            getSharedPreferences("manual_learning_v1",MODE_PRIVATE).edit().clear().apply();
            getSharedPreferences("manual_learning_v1",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();
            Toast.makeText(this,"Ручная разметка удалена, авторежим включён",Toast.LENGTH_LONG).show();
            finish();
        });
        setContentView(scroll);
    }

    private void addHeading(LinearLayout root,String text) {
        TextView t=new TextView(this); t.setText(text); t.setTextColor(ACCENT);
        t.setTextSize(13); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0,dp(10),0,dp(8)); root.addView(t);
    }
    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));
        p.setMargins(0,dp(7),0,dp(7)); return p;
    }
    private Button button(String text) {
        Button b=new Button(this); b.setText(text); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));
        return b;
    }
    private void addSlot(LinearLayout root,int index,String label) {
        Button b=button(label+": выбрать");
        slots[index]=b; root.addView(b,params());
        b.setOnClickListener(v -> chooseCard(index,label));
    }
    private void chooseCard(int index,String label) {
        List<PokerEngine.Card> options=new ArrayList<>();
        List<String> labels=new ArrayList<>();
        labels.add("Очистить слот"); options.add(null);
        for(String rank:ranks) for(int s=0;s<suits.length;s++) {
            String code=(rank.equals("10")?"T":rank)+suits[s];
            boolean used=false;
            for(int j=0;j<selected.length;j++) if(j!=index && selected[j]!=null && selected[j].toString().equals(code)) used=true;
            if(!used) {
                options.add(PokerEngine.Card.parse(code));
                labels.add(rank+symbols[s]);
            }
        }
        new AlertDialog.Builder(this).setTitle(label+" — выбери карту")
                .setItems(labels.toArray(new String[0]),(d,which)->{
                    selected[index]=options.get(which);
                    refresh();
                }).setNegativeButton("Отмена",null).show();
    }
    private String display(PokerEngine.Card card) {
        if(card==null) return "—";
        String code=card.toString(); char s=code.charAt(1);
        String symbol=s=='s'?"♠":s=='h'?"♥":s=='d'?"♦":"♣";
        String rank=code.substring(0,1).equals("T")?"10":code.substring(0,1);
        return rank+symbol;
    }
    private void refresh() {
        for(int i=0;i<7;i++) if(slots[i]!=null) {
            String label=i==0?"Левая карта":i==1?"Правая карта":"Борд "+(i-1);
            slots[i].setText(label+": "+display(selected[i]));
        }
        if(status!=null) {
            int count=0; for(PokerEngine.Card c:selected) if(c!=null) count++;
            status.setText("Выбрано карт: "+count+". Для расчёта нужны обе карты игрока и минимум 3 общие карты.");
        }
    }
    private void saveLabels() {
        if(selected[0]==null || selected[1]==null) {
            Toast.makeText(this,"Сначала укажи обе карты игрока",Toast.LENGTH_LONG).show(); return;
        }
        if(selected[0].equals(selected[1])) {
            Toast.makeText(this,"Карты игрока не могут совпадать",Toast.LENGTH_LONG).show(); return;
        }
        int board=0;
        for(int i=2;i<7;i++) if(selected[i]!=null) board++;
        if(board==1 || board==2) {
            Toast.makeText(this,"Для борда выбери 0 или минимум 3 карты",Toast.LENGTH_LONG).show(); return;
        }
        for(int i=0;i<7;i++) if(selected[i]!=null)
            for(int j=i+1;j<7;j++) if(selected[j]!=null && selected[i].equals(selected[j])) {
                Toast.makeText(this,"Одна карта выбрана дважды",Toast.LENGTH_LONG).show(); return;
            }
        SharedPreferences.Editor e=getSharedPreferences("manual_learning_v1",MODE_PRIVATE).edit();
        for(int i=0;i<7;i++) e.putString("card_"+i,selected[i]==null?"":selected[i].toString());
        e.putBoolean("enabled",true).apply();
        Toast.makeText(this,"Разметка сохранена и включена. Вернись в анализатор.",Toast.LENGTH_LONG).show();
        finish();
    }
}
