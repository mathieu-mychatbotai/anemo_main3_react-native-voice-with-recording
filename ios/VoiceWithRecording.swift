import Foundation
import Speech
import AVFoundation

@objc(VoiceWithRecording)
class VoiceWithRecording: RCTEventEmitter {
    
    private var speechRecognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var audioEngine = AVAudioEngine()
    private var audioFile: AVAudioFile?
    private var isRecording = false
    
    override init() {
        super.init()
        // *** CONFIGURATION POUR LE FRANÇAIS ***
        let frenchLocale = Locale(identifier: "fr-FR")
        speechRecognizer = SFSpeechRecognizer(locale: frenchLocale)
    }
    
    override func supportedEvents() -> [String]! {
        return ["onTranscript", "onPartialTranscript", "onError", "onReadyForSpeech", "onEndOfSpeech"]
    }
    
    @objc
    func startVoiceRecording(_ resolve: @escaping RCTPromiseResolveBlock,
                            rejecter reject: @escaping RCTPromiseRejectBlock) {
        
        // Vérifier si déjà en cours d'enregistrement
        if isRecording {
            reject("ALREADY_RECORDING", "Recording already in progress", nil)
            return
        }
        
        // Vérifier les permissions
        checkPermissions { [weak self] granted in
            guard granted else {
                reject("PERMISSION_DENIED", "Speech recognition or microphone permission denied", nil)
                return
            }
            
            guard let self = self else { return }
            
            // Vérifier la disponibilité de la reconnaissance vocale
            guard let speechRecognizer = self.speechRecognizer, speechRecognizer.isAvailable else {
                reject("SPEECH_NOT_AVAILABLE", "Speech recognition not available for French", nil)
                return
            }
            
            do {
                try self.startRecordingSession()
                self.isRecording = true
                resolve(nil)
            } catch {
                reject("AUDIO_INIT_ERROR", "Failed to start recording: \(error.localizedDescription)", error)
            }
        }
    }
    
    private func checkPermissions(completion: @escaping (Bool) -> Void) {
        // Vérifier la permission de reconnaissance vocale
        SFSpeechRecognizer.requestAuthorization { authStatus in
            DispatchQueue.main.async {
                guard authStatus == .authorized else {
                    completion(false)
                    return
                }
                
                // Vérifier la permission du microphone
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    completion(granted)
                }
            }
        }
    }
    
    private func startRecordingSession() throws {
        // Annuler toute tâche en cours
        recognitionTask?.cancel()
        recognitionTask = nil
        
        // Configurer la session audio
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.record, mode: .measurement, options: .duckOthers)
        try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        
        // Créer la requête de reconnaissance
        recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
        
        guard let recognitionRequest = recognitionRequest else {
            throw NSError(domain: "VoiceWithRecording", code: -1, 
                         userInfo: [NSLocalizedDescriptionKey: "Unable to create recognition request"])
        }
        
        recognitionRequest.shouldReportPartialResults = true
        
        // *** CONFIGURATION FRANÇAISE ADDITIONNELLE ***
        if #available(iOS 13, *) {
            recognitionRequest.requiresOnDeviceRecognition = false
        }
        
        // Configurer le moteur audio
        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        
        // Préparer le fichier pour l'enregistrement
        let audioFilePath = NSTemporaryDirectory() + "recorded_audio.wav"
        let audioFileURL = URL(fileURLWithPath: audioFilePath)
        
        // Supprimer le fichier existant s'il y en a un
        try? FileManager.default.removeItem(at: audioFileURL)
        
        audioFile = try AVAudioFile(forWriting: audioFileURL,
                                   settings: recordingFormat.settings,
                                   commonFormat: recordingFormat.commonFormat,
                                   interleaved: recordingFormat.isInterleaved)
        
        // Installer le tap pour capturer l'audio
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { [weak self] buffer, _ in
            guard let self = self else { return }
            
            // Envoyer le buffer à la reconnaissance vocale
            self.recognitionRequest?.append(buffer)
            
            // Écrire dans le fichier
            do {
                try self.audioFile?.write(from: buffer)
            } catch {
                print("Error writing audio buffer: \(error)")
            }
        }
        
        // Démarrer le moteur audio
        audioEngine.prepare()
        try audioEngine.start()
        
        // Envoyer l'événement "prêt"
        sendEvent(withName: "onReadyForSpeech", body: nil)
        
        // Démarrer la reconnaissance vocale
        recognitionTask = speechRecognizer?.recognitionTask(with: recognitionRequest) { [weak self] result, error in
            guard let self = self else { return }
            
            if let error = error {
                self.sendEvent(withName: "onError", body: ["error": error.localizedDescription])
                return
            }
            
            if let result = result {
                let transcription = result.bestTranscription.formattedString
                
                if result.isFinal {
                    self.sendEvent(withName: "onTranscript", 
                                 body: ["transcription": transcription])
                    self.sendEvent(withName: "onEndOfSpeech", body: nil)
                } else {
                    self.sendEvent(withName: "onPartialTranscript",
                                 body: ["transcription": transcription, "isFinal": false])
                }
            }
        }
    }
    
    @objc
    func stopVoiceRecording(_ resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
        
        if !isRecording {
            reject("NOT_RECORDING", "No recording in progress", nil)
            return
        }
        
        // Arrêter l'enregistrement
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        
        recognitionRequest?.endAudio()
        recognitionTask?.cancel()
        
        recognitionRequest = nil
        recognitionTask = nil
        
        isRecording = false
        
        // Retourner le chemin du fichier
        let audioFilePath = NSTemporaryDirectory() + "recorded_audio.wav"
        resolve(audioFilePath)
    }
    
    @objc
    override static func requiresMainQueueSetup() -> Bool {
        return true
    }
}

// Fichier Bridge Objective-C (VoiceWithRecording.m)
/*
#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

@interface RCT_EXTERN_MODULE(VoiceWithRecording, RCTEventEmitter)

RCT_EXTERN_METHOD(startVoiceRecording:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(stopVoiceRecording:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

@end
*/
