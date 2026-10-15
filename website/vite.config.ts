import { defineConfig } from 'vite';

export default defineConfig({
  base: '/SenseField/',
  build: {
    target: 'es2022',
    rollupOptions: {
      output: {
        manualChunks: {
          'field-renderer': ['three'],
        },
      },
    },
  },
});
