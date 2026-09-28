import { createApp } from 'vue'
import App from './App.vue'
import { login } from './auth'
import './style.css'

login()
  .then(() => createApp(App).mount('#app'))
  .catch((e: unknown) => {
    document.getElementById('app')!.textContent = `無法連線到 SSO：${String(e)}`
  })
