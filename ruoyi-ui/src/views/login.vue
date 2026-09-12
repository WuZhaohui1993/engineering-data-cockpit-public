<template>
  <main class="login">
    <div class="engineering-scene" aria-hidden="true">
      <img src="@/assets/images/login-industrial-campus.webp" alt="" draggable="false" fetchpriority="high" />
    </div>
    <div class="visual-shade" aria-hidden="true"></div>

    <section class="login-visual" aria-labelledby="platform-title">
      <div class="platform-identity">
        <span class="platform-mark" aria-hidden="true">
          <svg-icon icon-class="dashboard" />
        </span>
        <span>工程数字化管理平台</span>
      </div>
      <div class="platform-intro">
        <h1 id="platform-title">{{ title }}</h1>
        <p>统一管理项目、数据与可视化大屏</p>
      </div>
      <div class="visual-caption" aria-hidden="true">
        <span>项目管理</span>
        <span>数据集成</span>
        <span>可视化大屏</span>
      </div>
    </section>

    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-content">
        <header class="login-heading">
          <h2 id="login-title">账号登录</h2>
          <span class="heading-line" aria-hidden="true"></span>
        </header>
        <el-form
          ref="loginRef"
          :model="loginForm"
          :rules="loginRules"
          class="login-form"
          :hide-required-asterisk="true"
          @submit.prevent
        >
          <el-form-item prop="username">
            <el-input
              v-model="loginForm.username"
              type="text"
              auto-complete="off"
              placeholder="请输入账号"
              aria-label="账号"
              @keyup.enter="handleLogin"
            >
              <template #prefix><el-icon class="input-icon" aria-hidden="true"><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              auto-complete="off"
              placeholder="请输入密码"
              aria-label="密码"
              @keyup.enter="handleLogin"
            >
              <template #prefix><el-icon class="input-icon" aria-hidden="true"><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="captchaEnabled" prop="code">
            <div class="captcha-row">
              <el-input
                v-model="loginForm.code"
                auto-complete="off"
                placeholder="请输入验证码"
                aria-label="验证码"
                @keyup.enter="handleLogin"
              />
              <button type="button" class="login-code" aria-label="更换验证码图片" title="点击刷新验证码" @click="getCode">
                <img :src="codeUrl" alt="验证码" class="login-code-img" />
              </button>
              <el-button class="captcha-refresh" text :icon="RefreshRight" aria-label="刷新验证码" title="刷新验证码" @click="getCode" />
            </div>
          </el-form-item>
          <div class="login-options">
            <el-checkbox v-model="loginForm.rememberMe">记住密码</el-checkbox>
            <router-link v-if="register" class="register-link" :to="'/register'">立即注册</router-link>
          </div>
          <el-form-item class="login-submit">
            <el-button :loading="loading" type="primary" @click.prevent="handleLogin">
              <span>{{ loading ? '登录中…' : '登录' }}</span>
            </el-button>
          </el-form-item>
        </el-form>
      </div>
      <footer class="el-login-footer">{{ footerContent }}</footer>
    </section>
  </main>
</template>

<script setup>
import { Lock, RefreshRight, User } from "@element-plus/icons-vue"
import { getCodeImg } from "@/api/login"
import Cookies from "js-cookie"
import { encrypt, decrypt } from "@/utils/jsencrypt"
import useUserStore from '@/store/modules/user'
import defaultSettings from '@/settings'

const title = import.meta.env.VITE_APP_TITLE
const footerContent = defaultSettings.footerContent
const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()

const loginForm = ref({
  username: "admin",
  password: "admin123",
  rememberMe: false,
  code: "",
  uuid: ""
})

const loginRules = {
  username: [{ required: true, trigger: "blur", message: "请输入您的账号" }],
  password: [{ required: true, trigger: "blur", message: "请输入您的密码" }],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }]
}

const codeUrl = ref("")
const loading = ref(false)
// 验证码开关
const captchaEnabled = ref(true)
// 注册开关
const register = ref(false)
const redirect = ref(undefined)

watch(route, (newRoute) => {
    redirect.value = newRoute.query && newRoute.query.redirect
}, { immediate: true })

function handleLogin() {
  proxy.$refs.loginRef.validate(valid => {
    if (valid) {
      loading.value = true
      // 勾选了需要记住密码设置在 cookie 中设置记住用户名和密码
      if (loginForm.value.rememberMe) {
        Cookies.set("username", loginForm.value.username, { expires: 30 })
        Cookies.set("password", encrypt(loginForm.value.password), { expires: 30 })
        Cookies.set("rememberMe", loginForm.value.rememberMe, { expires: 30 })
      } else {
        // 否则移除
        Cookies.remove("username")
        Cookies.remove("password")
        Cookies.remove("rememberMe")
      }
      // 调用action的登录方法
      userStore.login(loginForm.value).then(() => {
        const query = route.query
        const otherQueryParams = Object.keys(query).reduce((acc, cur) => {
          if (cur !== "redirect") {
            acc[cur] = query[cur]
          }
          return acc
        }, {})
        router.push({ path: redirect.value || "/", query: otherQueryParams })
      }).catch(() => {
        loading.value = false
        // 重新获取验证码
        if (captchaEnabled.value) {
          getCode()
        }
      })
    }
  })
}

function getCode() {
  getCodeImg().then(res => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled
    if (captchaEnabled.value) {
      codeUrl.value = "data:image/gif;base64," + res.img
      loginForm.value.uuid = res.uuid
    }
  })
}

function getCookie() {
  const username = Cookies.get("username")
  const password = Cookies.get("password")
  const rememberMe = Cookies.get("rememberMe")
  loginForm.value = {
    username: username === undefined ? loginForm.value.username : username,
    password: password === undefined ? loginForm.value.password : decrypt(password),
    rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
  }
}

getCode()
getCookie()
</script>

<style lang="scss" scoped>
.login {
  --login-control-size: var(--el-component-size);
  --login-field-height: calc(var(--login-control-size) + 22px);
  position: relative;
  isolation: isolate;
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(360px, 31.4vw, 600px);
  gap: clamp(36px, 5vw, 96px);
  min-height: 100svh;
  padding: clamp(30px, 5.1vh, 56px) 4.4vw clamp(28px, 4.5vh, 48px);
  color: var(--el-text-color-primary);
  background: #061727;
}

.engineering-scene,
.visual-shade {
  position: absolute;
  z-index: -1;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.engineering-scene {
  animation: scene-enter 900ms ease-out both;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center;
    user-select: none;
  }
}

.visual-shade {
  background: linear-gradient(180deg, rgba(3, 15, 29, .08), transparent 30%, transparent 80%, rgba(3, 15, 29, .3));
}

.login-visual {
  display: flex;
  flex-direction: column;
  min-width: 0;
  color: #eef6ff;
}

.platform-identity {
  display: flex;
  align-items: center;
  gap: 14px;
  color: #d1e3f2;
  font-size: clamp(13px, 1vw, 18px);
  letter-spacing: 2px;
  animation: login-enter 600ms ease-out both;
}

.platform-mark {
  display: grid;
  place-items: center;
  width: clamp(34px, 2.4vw, 46px);
  height: clamp(34px, 2.4vw, 46px);
  border: 1px solid rgba(151, 216, 255, .55);
  border-radius: 10px;
  background: rgba(47, 151, 221, .1);
  color: #a7e3ff;
  font-size: clamp(19px, 1.4vw, 26px);
}

.platform-intro {
  margin-top: clamp(34px, 5.1vh, 58px);
  animation: login-enter 700ms 80ms ease-out both;

  h1 {
    margin: 0 0 12px;
    font-size: clamp(34px, 3.65vw, 70px);
    font-weight: 700;
    line-height: 1.25;
    letter-spacing: 2px;
    overflow-wrap: anywhere;
    text-shadow: 0 2px 16px rgba(0, 17, 36, .16);
  }

  p {
    margin: 0;
    color: #a5cfee;
    font-size: clamp(14px, 1.35vw, 24px);
    line-height: 1.7;
    letter-spacing: 1px;
  }
}

.visual-caption {
  position: relative;
  display: flex;
  align-items: center;
  gap: 28px;
  margin-top: auto;
  padding-top: 22px;
  border-top: 1px solid rgba(158, 201, 229, .3);
  color: #c1d7e9;
  font-size: clamp(12px, .95vw, 16px);
  letter-spacing: 2px;

  &::before {
    position: absolute;
    top: -2px;
    left: 0;
    width: 40px;
    height: 3px;
    border-radius: 2px;
    background: #60d9f7;
    content: '';
  }

  span + span::before {
    display: inline-block;
    width: 3px;
    height: 3px;
    margin: 0 28px 3px 0;
    border-radius: 50%;
    background: #8bdcf1;
    content: '';
  }
}

.login-panel {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
  padding-block: 40px 60px;
}

.login-content {
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  width: 100%;
  min-height: clamp(540px, 70.8svh, 780px);
  padding: clamp(42px, 7.2vh, 80px) clamp(28px, 3vw, 58px);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 16px;
  background: var(--el-bg-color-overlay);
  background: linear-gradient(145deg, var(--el-bg-color-overlay), var(--el-fill-color-light));
  box-shadow: 0 16px 64px rgba(0, 15, 35, .2);
  animation: login-enter 750ms 140ms ease-out both;
}

.login-heading {
  margin-bottom: clamp(32px, 4.9vh, 54px);

  h2 {
    margin: 0;
    color: var(--el-text-color-primary);
    font-size: clamp(26px, 2.1vw, 36px);
    font-weight: 600;
    line-height: 1.4;
    letter-spacing: 1px;
  }
}

.heading-line {
  display: block;
  width: 50px;
  height: 4px;
  margin-top: 20px;
  border-radius: 2px;
  background: var(--el-color-primary);
}

.login-form {
  :deep(.el-form-item) { margin-bottom: 28px; }
  :deep(.el-form-item__content) { min-width: 0; }

  :deep(.el-input__wrapper) {
    min-height: var(--login-field-height);
    padding: 0 16px;
    border-radius: 6px;
    background: var(--el-fill-color-blank);
    transition: box-shadow 180ms ease, background-color 180ms ease;
  }

  :deep(.el-input__inner) {
    height: calc(var(--login-field-height) - 2px);
    font-size: var(--el-font-size-medium);
  }

  .input-icon {
    width: 22px;
    margin-right: 10px;
    color: var(--el-text-color-regular);
    font-size: 22px;
  }

  :deep(.el-checkbox__label) {
    color: var(--el-text-color-regular);
    font-size: var(--el-font-size-medium);
    font-weight: 400;
  }
}

.captcha-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;

  .el-input { flex: 1; min-width: 0; }
}

.login-code {
  display: flex;
  flex: 0 0 clamp(84px, 8.3vw, 140px);
  align-items: center;
  justify-content: center;
  height: var(--login-field-height);
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--el-border-color);
  border-radius: 5px;
  background: var(--el-fill-color-blank);
  cursor: pointer;
  transition: border-color 180ms ease;

  &:hover { border-color: var(--el-color-primary); }
  &:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
}

.login-code-img { display: block; width: 100%; height: 100%; object-fit: contain; }

.captcha-refresh.el-button {
  flex: 0 0 var(--login-control-size);
  width: var(--login-control-size);
  height: var(--login-field-height);
  margin: 0;
  padding: 0;
  color: var(--el-text-color-regular);
  font-size: 22px;

  &:hover { color: var(--el-color-primary); }
  &:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
  :deep(.el-icon) { transition: transform 220ms ease; }
  &:hover :deep(.el-icon) { transform: rotate(45deg); }
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 6px 0 28px;
}

.register-link {
  color: var(--el-color-primary);
  font-size: 14px;
  &:hover { text-decoration: underline; }
  &:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
}

.login-form :deep(.login-submit) {
  margin-bottom: 0;

  .el-button {
    width: 100%;
    height: calc(var(--login-field-height) + 4px);
    border-radius: 6px;
    font-size: var(--el-font-size-large);
    font-weight: 500;
    transition: background-color 180ms ease, border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;

    &:hover:not(.is-loading) { transform: translateY(-1px); box-shadow: 0 5px 16px var(--el-color-primary-light-7); }
    &:active:not(.is-loading) { transform: translateY(0); }
  }
}

.el-login-footer {
  position: absolute;
  right: 0;
  bottom: 0;
  max-width: 100%;
  color: #bbcee1;
  font-size: clamp(12px, .9vw, 16px);
  line-height: 1.8;
  text-align: right;
  overflow-wrap: anywhere;
}

html[data-ui-size='large'] .login { --login-control-size: var(--el-component-size-large); }
html[data-ui-size='small'] .login { --login-control-size: var(--el-component-size-small); }

@keyframes login-enter {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes scene-enter { from { opacity: 0; } to { opacity: 1; } }

@media (min-width: 901px) and (max-height: 800px) {
  .login { padding-block: 28px; }
  .login-panel { padding-block: 24px 44px; }
  .login-content { min-height: 520px; padding-block: 32px; }
  .login-heading { margin-bottom: 30px; }
  .login-form :deep(.el-form-item) { margin-bottom: 24px; }
  .login-form :deep(.login-submit) { margin-bottom: 0; }
  .login-options { margin-bottom: 24px; }
}

@media (max-width: 1100px) {
  .login { gap: 30px; }
  .visual-caption { gap: 16px; letter-spacing: 1px; }
  .visual-caption span + span::before { margin-right: 16px; }
}

@media (max-width: 900px) {
  .login { grid-template-columns: minmax(0, 1fr); gap: 30px; padding: 28px; }
  .engineering-scene img { object-position: 36% center; }
  .visual-shade { background: linear-gradient(180deg, rgba(3, 15, 29, .35), rgba(3, 15, 29, .1) 28%, rgba(3, 15, 29, .5)); }
  .platform-identity { gap: 12px; font-size: 12px; }
  .platform-mark { width: 32px; height: 32px; font-size: 18px; }
  .platform-intro { margin-top: 28px; }
  .platform-intro h1 { margin-bottom: 8px; font-size: clamp(27px, 4.5vw, 36px); }
  .platform-intro p { font-size: 13px; letter-spacing: .5px; }
  .visual-caption { display: none; }
  .login-panel { flex-direction: column; gap: 24px; padding: 0; }
  .login-content { max-width: 460px; min-height: 0; padding: 32px; border-radius: 14px; }
  .login-heading { margin-bottom: 30px; }
  .login-heading h2 { font-size: 26px; }
  .heading-line { width: 40px; margin-top: 14px; }
  .login-code { flex-basis: 94px; }
  .el-login-footer { position: static; font-size: 12px; text-align: center; }
}

@media (max-width: 480px) {
  .login { padding: 24px 20px; }
  .login-content { padding: 30px 24px; }
  .login-form :deep(.el-input__wrapper) { padding-inline: 12px; }
  .login-form :deep(.el-input__inner) { font-size: var(--el-font-size-base); }
  .login-form .input-icon { width: 20px; margin-right: 6px; font-size: 20px; }
  .captcha-row { gap: 8px; }
  .login-code { flex-basis: 82px; }
}

@media (max-width: 360px) {
  .login { padding-inline: 16px; }
  .login-content { padding-inline: 20px; }
  .platform-intro h1 { font-size: 25px; }
  .platform-intro p { font-size: 12px; letter-spacing: 0; }
  .captcha-row { gap: 6px; }
  .login-code { flex-basis: 74px; }
}

@media (prefers-reduced-motion: reduce) {
  .platform-identity, .platform-intro, .engineering-scene, .login-content { animation: none; }
  .login-code, .captcha-refresh :deep(.el-icon), .login-form :deep(.el-input__wrapper), .login-form :deep(.el-button) { transition: none; }
}
</style>
