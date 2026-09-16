package com.example.colorconverter;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.converter.NumberStringConverter;

public class HelloController {
    @FXML private Rectangle box;
    @FXML private ColorPicker picker;
    @FXML private Label warn;

    @FXML private Slider rS, gS, bS, cS, mS, yS, kS, hS, lS, sS;
    @FXML private TextField rF, gF, bF, cF, mF, yF, kF, hF, lF, sF;

    private boolean updating = false;

    @FXML
    public void initialize() {
        bind(rS, rF); bind(gS, gF); bind(bS, bF);
        bind(cS, cF); bind(mS, mF); bind(yS, yF); bind(kS, kF);
        bind(hS, hF); bind(lS, lF); bind(sS, sF);

        rS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromRGB(); });
        gS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromRGB(); });
        bS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromRGB(); });

        cS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromCMYK(); });
        mS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromCMYK(); });
        yS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromCMYK(); });
        kS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromCMYK(); });

        hS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromHLS(); });
        lS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromHLS(); });
        sS.valueProperty().addListener((o, oldV, n) -> { if (!updating) updateFromHLS(); });

        picker.setOnAction(e -> {
            if (updating) return;
            Color c = picker.getValue();
            rS.setValue(c.getRed() * 255); gS.setValue(c.getGreen() * 255); bS.setValue(c.getBlue() * 255);
            updateFromRGB();
        });

        updateFromRGB();
    }

    private void bind(Slider s, TextField f) {
        f.textProperty().bindBidirectional(s.valueProperty(), new NumberStringConverter("#"));
        f.setOnAction(e -> {
            try {
                double v = Double.parseDouble(f.getText());
                if (v < 0 || v > s.getMax()) {
                    warn.setText("Выход за границы! Значение обрезано.");
                    s.setValue(Math.max(0, Math.min(s.getMax(), v)));
                } else warn.setText("");
            } catch (Exception ex) { warn.setText("Ошибка ввода!"); }
        });
    }

    // 1. RGB -> CMYK и RGB -> HLS по формулам из методички
    private void updateFromRGB() {
        updating = true;
        double R = rS.getValue(), G = gS.getValue(), B = bS.getValue();
        Color color = Color.rgb((int)Math.round(R), (int)Math.round(G), (int)Math.round(B));
        box.setFill(color); picker.setValue(color);

        // --- RGB -> CMYK (Формула 1 из листочка) ---
        double rN = R / 255.0, gN = G / 255.0, bN = B / 255.0;
        double K = Math.min(1 - rN, Math.min(1 - gN, 1 - bN));

        double C = (K == 1) ? 0 : (1 - rN - K) / (1 - K);
        double M = (K == 1) ? 0 : (1 - gN - K) / (1 - K);
        double Y = (K == 1) ? 0 : (1 - bN - K) / (1 - K);

        cS.setValue(C * 100); mS.setValue(M * 100);
        yS.setValue(Y * 100); kS.setValue(K * 100);

        // --- RGB -> HLS ---
        double max = Math.max(rN, Math.max(gN, bN));
        double min = Math.min(rN, Math.min(gN, bN));
        double d = max - min;

        double L = (max + min) / 2.0;
        double S = (d == 0) ? 0 : (L < 0.5 ? d / (max + min) : d / (2.0 - max - min));
        double H = 0;

        if (d != 0) {
            if (max == rN) H = 60 * (((gN - bN) / d) % 6);
            else if (max == gN) H = 60 * (((bN - rN) / d) + 2);
            else H = 60 * (((rN - gN) / d) + 4);
        }
        if (H < 0) H += 360;

        hS.setValue(H); lS.setValue(L * 100); sS.setValue(S * 100);
        updating = false;
    }

    // 2. CMYK -> RGB (Формула 1 из листочка)
    private void updateFromCMYK() {
        updating = true;
        double C = cS.getValue() / 100.0, M = mS.getValue() / 100.0;
        double Y = yS.getValue() / 100.0, K = kS.getValue() / 100.0;

        double R = 255 * (1 - C) * (1 - K);
        double G = 255 * (1 - M) * (1 - K);
        double B = 255 * (1 - Y) * (1 - K);

        rS.setValue(R); gS.setValue(G); bS.setValue(B);
        updating = false;
        updateFromRGB();
    }

    // 3. HLS -> RGB (Строго по блок-схеме с картинки 3)
    private void updateFromHLS() {
        updating = true;
        double H = hS.getValue();
        double L = lS.getValue() / 100.0;
        double S = sS.getValue() / 100.0;

        double R, G, B;

        if (S == 0) {
            // Если S=0, то цвет серый
            R = L * 255;
            G = L * 255;
            B = L * 255;
        } else {
            // Ветка блок-схемы
            double m2 = (L < 0.5) ? L * (1 + S) : L + S - L * S;
            double m1 = 2 * L - m2;

            R = valueFromHLS(H + 120, m1, m2) * 255;
            G = valueFromHLS(H, m1, m2) * 255;
            B = valueFromHLS(H - 120, m1, m2) * 255;
        }

        rS.setValue(R); gS.setValue(G); bS.setValue(B);
        updating = false;
        updateFromRGB();
    }

    // Вспомогательная функция Value(H, M1, M2) из алгоритма HLS
    private double valueFromHLS(double n, double m1, double m2) {
        if (n > 360) n -= 360;
        else if (n < 0) n += 360;

        if (n < 60) return m1 + (m2 - m1) * n / 60.0;
        if (n < 180) return m2;
        if (n < 240) return m1 + (m2 - m1) * (240 - n) / 60.0;
        return m1;
    }
}