<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api, errorMessage, json, type Mode } from '../api'

interface ModelSettings {
  baseUrl: string
  chatModel: string
  embeddingModel: string
  hasKey: boolean
  mode: Mode
}

interface Provider extends Omit<ModelSettings, 'mode'> {
  id: string
  name: string
  active: boolean
}

const current = ref<ModelSettings>()
const providers = ref<Provider[]>([])
const active = computed(() => providers.value.find((provider) => provider.active))
const loading = ref(true)
const busy = ref(false)
const result = ref('')
const error = ref('')
const dialogError = ref('')
const testing = ref('')
const dialog = ref<HTMLDialogElement>()
const dialogMode = ref<'provider' | 'current' | 'delete' | 'offline'>('provider')
const selected = ref<Provider>()
const form = reactive({ name: '', baseUrl: '', chatModel: '', embeddingModel: '', apiKey: '' })
const deleting = computed(() => dialogMode.value === 'delete')
const clearing = computed(() => dialogMode.value === 'offline')
const editing = computed(() => !deleting.value && !clearing.value)
const dialogTitle = computed(() => {
  switch (dialogMode.value) {
    case 'delete': return '删除模型服务'
    case 'offline': return '切换到离线演示'
    case 'current': return '编辑当前模型'
    default: return selected.value ? '编辑模型服务' : '添加模型服务'
  }
})
const saveLabel = computed(() => {
  if (busy.value) return '正在保存…'
  if (deleting.value) return '确认删除'
  if (clearing.value) return '确认切换'
  return '保存配置'
})
const isBusy = computed(() => loading.value || busy.value)

async function load() {
  loading.value = true
  error.value = ''
  const [modelResponse, providersResponse] = await Promise.allSettled([
    api<ModelSettings>('/settings/model'),
    api<Provider[]>('/settings/models'),
  ])
  if (modelResponse.status === 'fulfilled') current.value = modelResponse.value
  else error.value = errorMessage(modelResponse.reason)
  if (providersResponse.status === 'fulfilled') providers.value = providersResponse.value
  else error.value = errorMessage(providersResponse.reason)
  loading.value = false
}

function open(mode: typeof dialogMode.value, provider?: Provider) {
  dialogMode.value = mode
  selected.value = provider
  dialogError.value = ''
  error.value = ''
  result.value = ''
  const data = mode === 'current' ? current.value : provider
  Object.assign(form, {
    name: provider?.name ?? '',
    baseUrl: data?.baseUrl ?? '',
    chatModel: data?.chatModel ?? '',
    embeddingModel: data?.embeddingModel ?? '',
    apiKey: '',
  })
  dialog.value?.showModal()
}

function close() {
  if (!busy.value) dialog.value?.close()
}

function providerPath(id: string) {
  return '/settings/models/' + encodeURIComponent(id)
}

async function save() {
  if (busy.value) return
  busy.value = true
  dialogError.value = ''
  const provider = selected.value
  const mode = dialogMode.value
  const data = {
    baseUrl: form.baseUrl.trim(),
    chatModel: form.chatModel.trim(),
    embeddingModel: form.embeddingModel.trim(),
    apiKey: form.apiKey.trim(),
  }

  try {
    if (mode === 'provider') {
      const options = json({ ...data, name: form.name.trim() })
      if (provider) {
        await api<Provider>(providerPath(provider.id), { ...options, method: 'PUT' })
      } else {
        await api<Provider>('/settings/models', options)
      }
      result.value = provider ? '模型服务已更新。' : '模型服务已添加。'
    } else if (mode === 'delete' && provider) {
      await api<void>(providerPath(provider.id), { method: 'DELETE' })
      result.value = '模型服务已删除。'
    } else {
      const payload = mode === 'offline' ? { baseUrl: '', chatModel: '', embeddingModel: '', apiKey: '' } : data
      await api<ModelSettings>('/settings/model', { ...json(payload), method: 'PUT' })
      result.value = mode === 'offline' ? '已切换到离线演示。' : '当前模型配置已保存。'
    }
    dialog.value?.close()
    await load()
    window.dispatchEvent(new Event('model-settings-changed'))
  } catch (failure) {
    dialogError.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function activate(provider: Provider) {
  if (isBusy.value || provider.active) return
  busy.value = true
  error.value = ''
  result.value = ''
  try {
    await api<Provider>(providerPath(provider.id) + '/activate', { method: 'POST' })
    result.value = '已启用「' + provider.name + '」。'
    await load()
    window.dispatchEvent(new Event('model-settings-changed'))
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function test(provider?: Provider) {
  if (isBusy.value) return
  busy.value = true
  testing.value = provider?.id ?? 'current'
  error.value = ''
  result.value = ''
  const path = provider ? providerPath(provider.id) + '/test' : '/settings/model/test'
  try {
    const response = await api<{ message: string }>(path, { method: 'POST' })
    result.value = provider ? provider.name + '：' + response.message : response.message
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
    testing.value = ''
  }
}

onMounted(() => void load())
</script>

<template>
  <section class="work-page">
    <header class="page-header">
      <div>
        <h1>系统设置</h1>
        <p>配置兼容服务，选择面试与知识库使用的聊天模型和向量模型。</p>
      </div>
    </header>

    <div class="panel-heading settings-heading">
      <h2>模型服务</h2>
      <button type="button" class="button primary" :disabled="isBusy" @click="open('provider')">＋ 添加模型服务</button>
    </div>

    <aside v-if="current" class="panel current-settings" aria-label="正在使用的模型">
      <div class="current-label">
        <strong>当前服务</strong>
        <span class="chip" :class="{ 'chip-completed': current.mode === 'model' }">
          {{ current.mode === 'model' ? '模型模式' : '离线演示' }}
        </span>
        <span v-if="current.mode === 'model'" class="current-model">{{ active?.name || current.chatModel }}</span>
      </div>
      <div class="actions">
        <button v-if="!active" type="button" class="text-button quiet" :disabled="isBusy" @click="open('current')">编辑当前配置</button>
        <button v-if="!active && current.mode === 'model'" type="button" class="text-button" :disabled="isBusy" @click="test()">
          {{ testing === 'current' ? '正在测试…' : '测试连接' }}
        </button>
        <button v-if="current.mode === 'model'" type="button" class="text-button quiet" :disabled="isBusy" @click="open('offline')">
          切换到离线演示
        </button>
      </div>
    </aside>

    <p v-if="result" class="notice" role="status">{{ result }}</p>
    <div v-if="error" class="settings-load-error">
      <p class="error" role="alert">{{ error }}</p>
      <button type="button" class="text-button" :disabled="isBusy" @click="load">重新加载</button>
    </div>

    <ul v-if="providers.length" class="settings-list" :aria-busy="loading">
      <li v-for="provider in providers" :key="provider.id">
        <article class="panel settings-card">
          <div class="settings-profile">
            <span class="empty-icon"><span class="glyph glyph-database" aria-hidden="true"></span></span>
            <div>
              <h3>{{ provider.name }}</h3>
              <span v-if="provider.active" class="chip">使用中</span>
              <small v-else class="muted">OpenAI 兼容服务</small>
            </div>
          </div>
          <div class="settings-body">
            <dl class="settings-values">
              <div><dt>Base URL</dt><dd>{{ provider.baseUrl }}</dd></div>
              <div><dt>聊天模型</dt><dd>{{ provider.chatModel }}</dd></div>
              <div><dt>向量模型</dt><dd>{{ provider.embeddingModel || '未配置' }}</dd></div>
              <div><dt>API Key</dt><dd>{{ provider.hasKey ? '已设置' : '未设置' }}</dd></div>
            </dl>

            <div class="actions settings-actions">
              <button type="button" class="text-button quiet" :disabled="isBusy" @click="open('provider', provider)">编辑</button>
              <button type="button" class="text-button" :disabled="isBusy" @click="test(provider)">
                {{ testing === provider.id ? '正在测试…' : '测试连接' }}
              </button>
              <button
                type="button"
                class="text-button"
                :disabled="isBusy || provider.active"
                @click="activate(provider)"
              >{{ provider.active ? '当前服务' : '启用服务' }}</button>
              <button type="button" class="text-button quiet remove-provider" :disabled="isBusy" @click="open('delete', provider)">删除</button>
            </div>
          </div>
        </article>
      </li>
    </ul>
    <div v-else-if="loading" class="panel empty-state"><p role="status">正在加载模型配置…</p></div>
    <div v-else-if="!error" class="panel empty-state">
      <span class="empty-icon"><span class="glyph glyph-settings" aria-hidden="true"></span></span>
      <strong>尚未添加模型服务</strong>
      <p>准备好服务地址、模型名称和 API Key 后即可添加。</p>
      <button type="button" class="button secondary" @click="open('provider')">添加第一项服务</button>
    </div>

    <dialog
      ref="dialog"
      class="form-dialog"
      aria-labelledby="settings-dialog-title"
      @cancel.prevent="close"
      @close="form.apiKey = ''"
    >
      <form class="field-list" @submit.prevent="save">
        <header class="panel-heading dialog-heading">
          <h2 id="settings-dialog-title">{{ dialogTitle }}</h2>
          <button type="button" class="icon-button" aria-label="关闭" :disabled="busy" @click="close">×</button>
        </header>
        <p v-if="deleting">删除「{{ selected?.name }}」及其保存的密钥？</p>
        <p v-else-if="clearing">停用当前模型，后续练习将使用离线演示。</p>
        <fieldset v-else class="settings-form" :disabled="busy">
          <label v-if="dialogMode === 'provider'" class="field full">
            <span>服务名称</span>
            <input v-model="form.name" type="text" maxlength="80" placeholder="例如，阿里云百炼" required>
          </label>
          <label class="field full">
            <span>服务地址</span>
            <input v-model="form.baseUrl" type="url" maxlength="280" placeholder="https://api.example.com/v1" required>
          </label>
          <label class="field">
            <span>聊天模型</span>
            <input v-model="form.chatModel" type="text" maxlength="100" required>
          </label>
          <label class="field">
            <span>向量模型</span>
            <input v-model="form.embeddingModel" type="text" maxlength="100" placeholder="留空使用关键词检索">
          </label>
          <label class="field full">
            <span>API Key</span>
            <input
              v-model="form.apiKey"
              type="password"
              maxlength="512"
              autocomplete="new-password"
              :required="!(dialogMode === 'current' ? current?.hasKey : selected?.hasKey)"
              :placeholder="(dialogMode === 'current' ? current?.hasKey : selected?.hasKey) ? '服务地址相同时可留空' : '输入服务密钥'"
            >
          </label>
        </fieldset>
        <p v-if="dialogError" class="error" role="alert">{{ dialogError }}</p>
        <footer class="actions dialog-actions">
          <button type="button" class="button secondary" :disabled="busy" @click="close">取消</button>
          <button class="button" :class="editing ? 'primary' : 'danger'" :disabled="busy" type="submit">{{ saveLabel }}</button>
        </footer>
      </form>
    </dialog>
  </section>
</template>

<style scoped>
.settings-heading { margin: 0 0 16px; }
.settings-heading h2 { font-size: 15px; }
.current-settings { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 7px 16px; margin-bottom: 12px; padding: 10px 16px; }
.current-label { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.current-model { color: var(--muted); overflow-wrap: anywhere; }
.settings-list { display: grid; gap: 12px; }
.settings-card { display: grid; grid-template-columns: minmax(150px, 175px) minmax(0, 1fr); gap: 26px; padding: 22px; }
.settings-profile { display: flex; min-width: 0; align-items: flex-start; gap: 11px; overflow-wrap: anywhere; }
.settings-profile .empty-icon { width: 34px; height: 34px; border-radius: 6px; flex: 0 0 34px; }
.settings-profile h3 { margin: 0 0 6px; font-size: 14px; }
.settings-values { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px 22px; margin: 0; }
.settings-values dt { color: var(--muted); font-size: 12px; }
.settings-values dd { margin: 6px 0 0; overflow-wrap: anywhere; }
.settings-actions { gap: 18px; border-top: 1px solid var(--line); margin-top: 17px; padding-top: 7px; }
.remove-provider { margin-left: auto; }
.settings-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.settings-form .full { grid-column: 1 / -1; }
.settings-load-error { margin-bottom: 12px; }
@media (max-width: 680px) {
  .settings-card { grid-template-columns: 1fr; gap: 16px; padding: 16px; }
  .settings-values { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 400px) {
  .settings-form, .settings-values { grid-template-columns: 1fr; }
  .remove-provider { margin-left: 0; }
}
</style>
