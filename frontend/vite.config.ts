// 개발 서버는 /api 와 /ws 를 로컬 앱(bootRun, 8080)으로 넘긴다. 화면과 API 가 같은 출처라 CORS 설정이 필요 없고,
// Refresh 쿠키(Path=/api/v1/auth, SameSite=Strict)도 그대로 붙는다.
// changeOrigin 은 켜지 않는다: Host 가 localhost:5173 으로 유지돼 서버의 WebSocket Origin 검사(같은 출처만)를 통과한다.
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const backend = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': backend,
      '/ws': { target: backend, ws: true },
    },
  },
  test: {
    include: ['test/**/*.test.ts'],
  },
})
