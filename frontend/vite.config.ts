import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      // The app, and 표 다듬기 (the spreadsheet screen, shown in a frame and loaded only when it is opened).
      input: { main: 'index.html', polish: 'polish.html' },
    },
    chunkSizeWarningLimit: 8000,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:19000',
    },
  },
})
