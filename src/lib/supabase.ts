import { createClient } from '@supabase/supabase-js';
import AsyncStorage from '@react-native-async-storage/async-storage';

const SUPABASE_URL = 'https://oetureonznkfcumdmact.supabase.co';
const SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im9ldHVyZW9uem5rZmN1bWRtYWN0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTk0NTgyODUsImV4cCI6MjA3NTAzNDI4NX0.VFWkkGOXp_Anrqa_I1p4h8BZ3b4IvO9AxeCCBfX76z8';

export const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
  auth: {
    storage: AsyncStorage,
    autoRefreshToken: true,
    persistSession: true,
    detectSessionInUrl: false,
  },
});
