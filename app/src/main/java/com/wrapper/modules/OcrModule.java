package com.wrapper.modules;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.wrapper.core.ModuleConfig;

/**
 * Модуль OCR (распознавание текста)
 */
public class OcrModule {
    private static final String TAG = "OcrModule";
    private TextRecognizer recognizer;
    private ModuleConfig config;
    private OcrListener listener;

    public interface OcrListener {
        void onTextRecognized(String text);
        void onOcrError(String error);
    }

    public OcrModule(Context context, ModuleConfig config, OcrListener listener) {
        this.config = config;
        this.listener = listener;
        initRecognizer();
    }

    private void initRecognizer() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Log.d(TAG, "OCR распознаватель инициализирован");
    }

    /**
     * Распознать текст из Bitmap
     */
    public void recognizeText(Bitmap bitmap) {
        if (bitmap == null) {
            if (listener != null) {
                listener.onOcrError("Bitmap is null");
            }
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
            .addOnSuccessListener(text -> {
                String resultText = text.getText();
                Log.d(TAG, "OCR результат: " + resultText);
                if (listener != null) {
                    listener.onTextRecognized(resultText);
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "OCR ошибка", e);
                if (listener != null) {
                    listener.onOcrError(e.getMessage());
                }
            });
    }

    /**
     * Распознать текст из файла
     */
    public void recognizeText(android.net.Uri imageUri, Context context) {
        try {
            InputImage image = InputImage.fromFilePath(context, imageUri);
            recognizer.process(image)
                .addOnSuccessListener(text -> {
                    if (listener != null) {
                        listener.onTextRecognized(text.getText());
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR ошибка", e);
                    if (listener != null) {
                        listener.onOcrError(e.getMessage());
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "Ошибка загрузки изображения", e);
            if (listener != null) {
                listener.onOcrError(e.getMessage());
            }
        }
    }

    /**
     * Освобождение ресурсов
     */
    public void close() {
        if (recognizer != null) {
            recognizer.close();
        }
    }
}
