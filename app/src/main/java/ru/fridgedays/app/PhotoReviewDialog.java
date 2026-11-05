package ru.fridgedays.app;

import android.app.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import ru.fridgedays.core.*;

/** A human confirmation boundary: the caller only receives explicitly selected suggestions. */
final class PhotoReviewDialog {
    private static final Map<String,String> LABELS=Map.of("name","Название","quantity","Количество","unit","Единица","manufactured","Дата изготовления","expiry","Годен до","shelfDays","Срок хранения, дней","openingDays","После открытия, дней");
    static AlertDialog show(Activity activity,PhotoDraft draft,Consumer<Map<String,String>> apply){
        LinearLayout body=Ui.column(activity);body.setPadding(24,8,24,24);ScrollView scroll=new ScrollView(activity);scroll.addView(body);
        body.addView(Ui.text(activity,"Сверьте данные с упаковкой. Перенос в форму ещё не сохраняет продукт. Каждую дату отметьте вручную.",14,Ui.INK));
        Map<String,CheckBox> choices=new LinkedHashMap<>();
        for(String key:List.of("name","quantity","unit","openingDays","manufactured","shelfDays","expiry")){
            PhotoDraft.Value value=draft.fields.get(key);if(value==null)continue;
            CheckBox box=new CheckBox(activity);box.setText(activity.getString(R.string.recognition_field,LABELS.get(key),value.text,value.calculated?" · рассчитано":""));box.setTextColor(Ui.INK);box.setTag("review-"+key);
            box.setChecked(!Set.of("manufactured","expiry","shelfDays").contains(key));body.addView(box);choices.put(key,box);
            body.addView(Ui.text(activity,"Источник: "+value.evidence,12,Ui.MUTED));
        }
        Map<String,Spinner> dates=new LinkedHashMap<>();for(String date:draft.unknownDates){
            Spinner role=Ui.dropdown(activity,"Дата "+date+" — назначьте по надписи на упаковке",List.of("Не переносить","Годен до","Дата изготовления"),0,body);role.setTag("unknown-"+date);dates.put(date,role);
        }
        if(draft.empty())body.addView(Ui.text(activity,"Не удалось найти поля. Попробуйте снимок ближе, ровнее и без бликов; можно заполнить вручную.",15,Ui.INK));
        for(String note:draft.notes)body.addView(Ui.text(activity,"• "+note,13,Ui.RED));
        if(!draft.recognizedText.isEmpty()){
            TextView raw=Ui.text(activity,draft.recognizedText,12,Ui.MUTED);raw.setTextIsSelectable(true);raw.setVisibility(android.view.View.GONE);
            body.addView(Ui.button(activity,"Показать распознанный текст",()->raw.setVisibility(raw.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE)));body.addView(raw);
        }
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Проверьте маркировку").setView(scroll).setPositiveButton("Перенести в форму",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            Map<String,String> selected=new LinkedHashMap<>();for(var entry:choices.entrySet())if(entry.getValue().isChecked())selected.put(entry.getKey(),draft.fields.get(entry.getKey()).text);
            if(selected.containsKey("quantity")!=selected.containsKey("unit"))throw new IllegalArgumentException("Отметьте количество и единицу вместе");
            for(var entry:dates.entrySet()){
                int role=entry.getValue().getSelectedItemPosition();if(role==0)continue;String key=role==1?"expiry":"manufactured";
                if(selected.containsKey(key)&&!selected.get(key).equals(entry.getKey()))throw new IllegalArgumentException("Для одного поля выбрано несколько дат. Оставьте одну");selected.put(key,entry.getKey());
            }
            if(selected.containsKey("expiry")&&selected.containsKey("manufactured")&&selected.get("expiry").compareTo(selected.get("manufactured"))<0)throw new IllegalArgumentException("Срок годности раньше даты изготовления. Проверьте назначения дат");
            apply.accept(selected);dialog.dismiss();
        }catch(Exception e){Ui.error(activity,e);}}));dialog.show();return dialog;
    }
}
