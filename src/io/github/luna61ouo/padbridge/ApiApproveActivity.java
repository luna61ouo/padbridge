package io.github.luna61ouo.padbridge;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 核准畫面：「某某想控制滑鼠，允許嗎？」
 * 防騙按：不對外開放（只有 Padbridge 自己的通知開得到）、隱藏別的 App 疊上來的畫面、
 * 被遮住時的點擊不算、按鈕 1 秒後才能按。
 */
public class ApiApproveActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        final String pkg = getIntent().getStringExtra("pkg");
        final String digest = getIntent().getStringExtra("digest");
        final String label = getIntent().getStringExtra("label");
        if (pkg == null || digest == null) { finish(); return; }

        int p = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p * 2, p, p);
        box.setFilterTouchesWhenObscured(true);

        TextView t = new TextView(this);
        t.setText("「" + label + "」想控制滑鼠");
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);

        TextView d = new TextView(this);
        String fp = digest.length() > 23 ? digest.substring(0, 23) : digest;
        d.setText("\n允許之後，它可以對目前選中的電腦：\n  ・捲動滾輪\n  ・點左鍵、右鍵\n\n它不能移動游標、不能打字。\n"
                + "它在操作時，通知列會顯示「正在控制滑鼠」，隨時可以按「撤銷」。\n\n"
                + "套件：" + pkg + "\n簽章：" + fp + "…\n\n不認得這個 App 的話，請按拒絕。");
        d.setTextSize(15);
        box.addView(d);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, p, 0, 0);
        final Button no = new Button(this), yes = new Button(this);
        no.setText("拒絕");
        yes.setText("允許");
        for (Button x : new Button[]{no, yes}) { x.setFilterTouchesWhenObscured(true); x.setEnabled(false); row.addView(x); }
        no.setOnClickListener(v -> { ApiApps.get(this).deny(pkg); Toast.makeText(this, "已拒絕", Toast.LENGTH_SHORT).show(); finish(); });
        yes.setOnClickListener(v -> {
            boolean ok = ApiApps.get(this).approve(pkg, digest, label);
            Toast.makeText(this, ok ? "已允許「" + label + "」" : "這個 App 在請求之後被換過，沒有允許", Toast.LENGTH_LONG).show();
            finish();
        });
        box.addView(row);
        setContentView(box);
        row.postDelayed(() -> { no.setEnabled(true); yes.setEnabled(true); }, 1000);
    }
}
