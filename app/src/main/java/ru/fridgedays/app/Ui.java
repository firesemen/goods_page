package ru.fridgedays.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import ru.fridgedays.core.Product;

final class Ui {
    static final int GREEN=Color.rgb(40,99,79),INK=Color.rgb(27,43,35),MUTED=Color.rgb(100,111,104),BG=Color.rgb(246,245,240),RED=Color.rgb(160,50,42);
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static TextView text(Context c,String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(c,4),0,dp(c,4));return t;}
    static TextView title(Context c,String value){TextView t=text(c,value,28,INK);t.setTypeface(null,Typeface.BOLD);return t;}
    static Button button(Context c,String text,Runnable action){Button b=new Button(c);b.setText(text);b.setAllCaps(false);b.setTextColor(GREEN);b.setOnClickListener(v->action.run());return b;}
    static EditText field(Context c,String label,String value,int type,LinearLayout into){into.addView(text(c,label,13,MUTED));EditText e=new EditText(c);e.setText(value);e.setTextColor(INK);e.setInputType(type);e.setSingleLine(true);into.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    static Spinner dropdown(Context c,String label,List<String> items,int selected,LinearLayout into){
        if(label!=null)into.addView(text(c,label,13,MUTED));Spinner spinner=new Spinner(c,Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(c,android.R.layout.simple_spinner_item,items);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);spinner.setSelection(selected);spinner.setMinimumHeight(dp(c,48));spinner.setContentDescription(label);into.addView(spinner,new LinearLayout.LayoutParams(-1,-2));return spinner;
    }
    static List<String> units(String current){List<String> units=new ArrayList<>(List.of("шт","г","кг","мл","л","упак","порц","бут","банка"));if(current!=null&&!units.contains(current))units.add(current);return units;}
    static List<String> dayOptions(){List<String> options=new ArrayList<>();for(int n=1;n<=365;n++){int last=n%10,ten=n%100;String word=ten>=11&&ten<=14?"дней":last==1?"день":last>=2&&last<=4?"дня":"дней";options.add(n+" "+word);}return options;}
    static int expiryColor(Product.ExpiryState state){return switch(state){case GREEN->GREEN;case YELLOW->Color.rgb(232,180,34);case RED->RED;case BLACK->Color.BLACK;};}
    static GradientDrawable background(int color,int radius,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static void card(Context c,View view){view.setBackground(background(Color.WHITE,18,c));view.setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14));}
    static void space(Context c,LinearLayout l,int height){Space s=new Space(c);l.addView(s,new LinearLayout.LayoutParams(1,dp(c,height)));}
    static void error(Context c,Exception e){String message=e.getMessage();new AlertDialog.Builder(c).setTitle("Не удалось завершить").setMessage(message==null?"Попробуйте ещё раз":message).setPositiveButton("Понятно",null).show();}
    static void inset(Activity activity,View root){root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});}
}
