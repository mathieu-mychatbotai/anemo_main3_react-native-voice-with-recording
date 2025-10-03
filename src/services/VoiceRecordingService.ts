import RNFS from 'react-native-fs';
import { supabase } from '../lib/supabase';
import { Platform } from 'react-native';

export class VoiceRecordingService {
  
  static async uploadAudioFile(localFilePath: string): Promise<string> {
    try {
      // Lire le fichier en base64
      const fileData = await RNFS.readFile(localFilePath, 'base64');
      
      // Obtenir l'ID utilisateur
      const { data: { user } } = await supabase.auth.getUser();
      if (!user) throw new Error('User not authenticated');
      
      // Créer un nom de fichier unique
      const fileExtension = Platform.OS === 'ios' ? 'wav' : 'pcm';
      const fileName = `${user.id}/${Date.now()}.${fileExtension}`;
      
      // Convertir base64 en Blob
      const blob = this.base64ToBlob(fileData, 
        Platform.OS === 'ios' ? 'audio/wav' : 'audio/pcm');
      
      // Uploader vers Supabase Storage
      const { data, error } = await supabase.storage
        .from('audio-recordings')
        .upload(fileName, blob, {
          contentType: Platform.OS === 'ios' ? 'audio/wav' : 'audio/pcm',
          cacheControl: '3600',
        });
      
      if (error) throw error;
      
      return data.path;
    } catch (error) {
      console.error('Error uploading audio:', error);
      throw error;
    }
  }
  
  static async saveTranscription(
    transcriptionText: string, 
    audioFilePath: string,
    durationSeconds?: number
  ) {
    try {
      const { data: { user } } = await supabase.auth.getUser();
      if (!user) throw new Error('User not authenticated');
      
      const { data, error } = await supabase
        .from('transcriptions')
        .insert([
          {
            user_id: user.id,
            transcription_text: transcriptionText,
            audio_file_path: audioFilePath,
            language: 'fr-FR',
            duration_seconds: durationSeconds,
          }
        ])
        .select()
        .single();
      
      if (error) throw error;
      
      return data;
    } catch (error) {
      console.error('Error saving transcription:', error);
      throw error;
    }
  }
  
  static async getTranscriptions(limit: number = 50) {
    try {
      const { data, error } = await supabase
        .from('transcriptions')
        .select('*')
        .order('created_at', { ascending: false })
        .limit(limit);
      
      if (error) throw error;
      
      return data;
    } catch (error) {
      console.error('Error fetching transcriptions:', error);
      throw error;
    }
  }
  
  static async getAudioFileUrl(filePath: string): Promise<string> {
    try {
      const { data } = await supabase.storage
        .from('audio-recordings')
        .createSignedUrl(filePath, 3600); // URL valide pendant 1 heure
      
      return data?.signedUrl || '';
    } catch (error) {
      console.error('Error getting audio URL:', error);
      throw error;
    }
  }
  
  private static base64ToBlob(base64: string, contentType: string): Blob {
    const byteCharacters = atob(base64);
    const byteArrays = [];
    
    for (let offset = 0; offset < byteCharacters.length; offset += 512) {
      const slice = byteCharacters.slice(offset, offset + 512);
      const byteNumbers = new Array(slice.length);
      
      for (let i = 0; i < slice.length; i++) {
        byteNumbers[i] = slice.charCodeAt(i);
      }
      
      const byteArray = new Uint8Array(byteNumbers);
      byteArrays.push(byteArray);
    }
    
    return new Blob(byteArrays, { type: contentType });
  }
}
