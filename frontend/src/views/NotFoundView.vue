<!-- 在地址没有匹配到页面时，显示提示和返回入口。 -->
<!-- 这个组件处理前端路由未匹配的情况。访问不存在的业务 API 则由后端按接口规则返回错误。 -->
<template>
  <main class="not-found"><span class="eyebrow">404 / PAGE NOT FOUND</span><h1>这个页面还没有课程。</h1><p class="muted">你访问的地址不存在，请返回工作台继续。</p><router-link to="/dashboard"><el-button type="primary">返回工作台</el-button></router-link></main>
</template>
