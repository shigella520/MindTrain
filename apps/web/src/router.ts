import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from './views/DashboardView.vue'
import TrainingView from './views/TrainingView.vue'
import AdminView from './views/AdminView.vue'
import SettingsView from './views/SettingsView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/train', name: 'training', component: TrainingView },
    { path: '/admin', name: 'admin', component: AdminView },
    { path: '/settings', name: 'settings', component: SettingsView },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
