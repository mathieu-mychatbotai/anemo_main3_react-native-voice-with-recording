package com.voicewithrecording;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public class VoiceWithRecordingModule extends ReactContextBaseJavaModule {
    private static final String MODULE_NAME = "VoiceWithRecording";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    private final ReactApplicationContext reactContext;
    private SpeechRecognizer speechRecognizer;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private File audioFile;

    public VoiceWithRecordingModule(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }

    @ReactMethod
    public void startVoiceRecording(Promise promise) {
        // Vérifier les permissions
        if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            promise.reject("PERMISSION_DENIED", "Microphone permission not granted");
            return;
        }

        // Vérifier si la reconnaissance vocale est disponible
        if (!SpeechRecognizer.isRecognitionAvailable(reactContext)) {
            promise.reject("SPEECH_NOT_AVAILABLE", "Speech recognition not available");
            return;
        }

        // Vérifier si déjà en cours d'enregistrement
        if (isRecording) {
            promise.reject("ALREADY_RECORDING", "Recording already in progress");
            return;
        }

        try {
            // Initialiser le fichier audio
            audioFile = new File(reactContext.getFilesDir(), "recorded_audio.pcm");
            
            // Initialiser la reconnaissance vocale
            initSpeechRecognizer();
            
            // Initialiser l'enregistrement audio
            initAudioRecording();
            
            isRecording = true;
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("AUDIO_INIT_ERROR", "Failed to initialize recording: " + e.getMessage());
        }
    }

    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(reactContext);
        
        Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        
        // *** CONFIGURATION POUR LE FRANÇAIS ***
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
                                 RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "fr-FR");
        
        // Paramètres additionnels
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, 
                                 reactContext.getPackageName());

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                sendEvent("onReadyForSpeech", null);
            }

            @Override
            public void onBeginningOfSpeech() {
                sendEvent("onBeginningOfSpeech", null);
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // Optionnel: envoyer le niveau audio
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
                // Non utilisé
            }

            @Override
            public void onEndOfSpeech() {
                sendEvent("onEndOfSpeech", null);
                // Redémarrer la reconnaissance pour un enregistrement continu
                if (isRecording) {
                    speechRecognizer.startListening(recognizerIntent);
                }
            }

            @Override
            public void onError(int error) {
                String errorMessage = getErrorText(error);
                WritableMap params = Arguments.createMap();
                params.putString("error", errorMessage);
                sendEvent("onError", params);
                
                // Redémarrer en cas d'erreur (sauf si c'est une erreur réseau)
                if (isRecording && error != SpeechRecognizer.ERROR_NETWORK) {
                    speechRecognizer.startListening(recognizerIntent);
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
                
                if (matches != null && !matches.isEmpty()) {
                    String transcription = matches.get(0);
                    WritableMap params = Arguments.createMap();
                    params.putString("transcription", transcription);
                    sendEvent("onTranscript", params);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
                
                if (matches != null && !matches.isEmpty()) {
                    String transcription = matches.get(0);
                    WritableMap params = Arguments.createMap();
                    params.putString("transcription", transcription);
                    params.putBoolean("isFinal", false);
                    sendEvent("onPartialTranscript", params);
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
                // Non utilisé
            }
        });

        speechRecognizer.startListening(recognizerIntent);
    }

    private void initAudioRecording() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        );

        audioRecord.startRecording();

        // Thread pour enregistrer l'audio dans un fichier
        recordingThread = new Thread(() -> {
            FileOutputStream outputStream = null;
            try {
                outputStream = new FileOutputStream(audioFile);
                byte[] buffer = new byte[bufferSize];
                
                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        outputStream.write(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        
        recordingThread.start();
    }

    @ReactMethod
    public void stopVoiceRecording(Promise promise) {
        if (!isRecording) {
            promise.reject("NOT_RECORDING", "No recording in progress");
            return;
        }

        try {
            isRecording = false;

            // Arrêter la reconnaissance vocale
            if (speechRecognizer != null) {
                speechRecognizer.stopListening();
                speechRecognizer.destroy();
                speechRecognizer = null;
            }

            // Arrêter l'enregistrement audio
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }

            // Attendre que le thread se termine
            if (recordingThread != null) {
                recordingThread.join(1000);
            }

            // Retourner le chemin du fichier
            promise.resolve(audioFile.getAbsolutePath());
        } catch (Exception e) {
            promise.reject("STOP_ERROR", "Failed to stop recording: " + e.getMessage());
        }
    }

    private void sendEvent(String eventName, WritableMap params) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
    }

    private String getErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Erreur audio";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Erreur client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Permissions insuffisantes";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Erreur réseau";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Timeout réseau";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "Aucune correspondance";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Reconnaissance occupée";
            case SpeechRecognizer.ERROR_SERVER:
                return "Erreur serveur";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Timeout de parole";
            default:
                return "Erreur inconnue";
        }
    }
}
