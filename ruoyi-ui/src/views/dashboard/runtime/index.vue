<template>
  <div class="runtime-route-root">
    <div
      ref="runtimePage"
      class="runtime-page"
      :class="{
        preview: isPreview,
        'embed-mode': isEmbedMode,
        'share-mode': isShareMode,
        'menu-mode': isMenuRuntime,
        'hide-scrollbar': schema?.canvas?.hideScrollbar === true,
        'theme-light': schema?.canvas?.theme === 'light',
        'is-fullscreen': isFullscreen,
      }"
    >
      <div
        v-if="loading"
        class="runtime-loading-mask"
        role="status"
        aria-live="polite"
      >
        加载中…
      </div>
      <div v-if="!isEmbedMode && !isShareMode && !isMenuRuntime" class="runtime-toolbar">
        <div class="runtime-title">
          <el-button link type="info" @click="back"
            ><el-icon><Back /></el-icon
            >{{
              revisionId ? "返回版本列表" : isPreview ? "返回设计器" : "返回"
            }}</el-button
          ><span class="toolbar-divider"></span
          ><span class="page-name">{{ runtime.pageName || "大屏运行态" }}</span
          ><el-tag v-if="runtime.versionNo" effect="dark">{{
            runtime.previewSource === "REVISION"
              ? `历史版本 V${runtime.versionNo}`
              : isPreview
                ? runtime.previewSource === "PUBLISHED"
                  ? "发布版本预览"
                  : "草稿预览"
                : `版本 ${runtime.versionNo}`
          }}</el-tag>
        </div>
        <div class="toolbar-right">
          <span v-if="runtime.preview" class="preview-note"
            ><el-icon><Edit /></el-icon
            >{{
              runtime.previewSource === "REVISION"
                ? "历史版本只读预览，不会改变草稿或运行版本"
                : runtime.previewSource === "PUBLISHED"
                  ? "当前无草稿，预览发布版本；不会改变运行版本"
                  : "预览不会改变运行版本"
            }}</span
          ><span v-if="lastLoaded">刷新 {{ lastLoaded }}</span
          ><el-button link :loading="refreshing" @click="refreshData()"
            ><el-icon><Refresh /></el-icon>刷新</el-button
          ><el-button
            v-if="!isPreview"
            v-hasPermi="['dashboard:page:edit']"
            link
            type="primary"
            @click="designer"
            ><el-icon><Setting /></el-icon>设计</el-button
          >
        </div>
      </div>
      <div
        v-if="!isEmbedMode && !isShareMode && !isMenuRuntime && pageFilters.length"
        class="runtime-filterbar"
      >
        <label v-for="filter in pageFilters" :key="filter.id"
          ><span>{{ filter.label || filter.parameter }}</span
          ><input
            v-model="pageFilterValues[filter.id]"
            :type="filterInputType(filter)"
            :step="filter.type === 'NUMBER' ? 'any' : undefined"
            :placeholder="
              filter.defaultValue ? `默认：${filter.defaultValue}` : '请输入'
            "
            @change="changePageFilter"
        /></label>
      </div>
      <div v-if="refreshNotice && !error" class="runtime-refresh-notice" role="status">{{ refreshNotice }}</div>
      <div v-if="error" class="runtime-error">
        <el-result icon="error" title="页面不可用" :sub-title="error"
          ><template #extra
            ><el-button type="primary" @click="isPreview ? back() : load()">{{
              revisionId
                ? "返回版本列表"
                : isPreview
                  ? "返回设计器"
                  : "重新加载"
            }}</el-button></template
          ></el-result
        >
      </div>
      <div
        v-else-if="schema"
        ref="stageViewport"
        class="stage-viewport"
        :style="stageViewportStyle"
        :class="{ 'embed-viewport': isEmbedMode || isShareMode, 'hide-scrollbar': schema?.canvas?.hideScrollbar === true }"
      >
        <div class="stage-holder" :style="stageHolderStyle">
          <div
            ref="stageElement"
            class="runtime-stage"
            :class="{ 'show-grid': showGrid }"
            :style="stageStyle"
            @click.self="clearActiveWidget"
          >
            <div
              v-if="
                schema.canvas?.watermark?.enabled &&
                schema.canvas.watermark.text
              "
              class="runtime-watermark"
              :style="runtimeWatermarkStyle"
            >
              {{ schema.canvas.watermark.text }}
            </div>
            <div
              v-for="widget in visibleWidgets"
              :key="widget.id"
              class="runtime-widget"
              :class="{
                active: activeWidgetId === widget.id,
                stale: widgetStates[widget.id]?.stale,
                'background-transparent':
                  widget.style?.backgroundTransparent === true,
                'border-transparent': widget.style?.borderTransparent === true,
                'widget-embedded': widget.style?.embeddedMode === true,
                'title-image': hasTitleImage(widget),
                'title-hidden':
                  !isWidgetTitleVisible(widget) &&
                  !hasWidgetQuality(widget),
              }"
              :style="widgetStyle(widget)"
              @click.stop="handleWidgetClick(widget)"
            >
              <div
                v-if="
                  isWidgetTitleVisible(widget) ||
                  hasWidgetQuality(widget)
                "
                class="widget-heading"
                :style="widgetHeadingStyle(widget)"
              >
                <span v-if="isWidgetTitleVisible(widget)" class="widget-heading-copy"
                  ><b
                    v-if="statisticsHeadingTitle(widget)"
                    :style="{
                      fontWeight: Number(widget.style?.titleFontWeight) || 600,
                    }"
                    >{{
                      statisticsHeadingTitle(widget)
                    }}</b
                  ><small
                    v-if="dashboardTitleText(widget.style?.subtitle)"
                    :style="{
                      color: widget.style?.subtitleColor || '#aebccc',
                      fontSize: `${Number(widget.style?.subtitleFontSize) || 10}px`,
                      fontWeight:
                        Number(widget.style?.subtitleFontWeight) || 400,
                    }"
                    >{{ dashboardTitleText(widget.style?.subtitle) }}</small
                  ></span
                ><span
                  v-if="hasWidgetQuality(widget)"
                  class="quality-badge"
                  :class="qualityClass(widgetStates[widget.id].quality)"
                  >{{ qualityLabel(widgetStates[widget.id].quality) }}</span
                >
              </div>
              <div
                v-if="
                  widgetStates[widget.id]?.quality &&
                  !['SUCCESS', 'NO_DATA', 'STALE'].includes(
                    widgetStates[widget.id].quality,
                  )
                "
                class="widget-state error-state"
              >
                <el-icon><WarningFilled /></el-icon
                ><span>{{ qualityLabel(widgetStates[widget.id].quality) }}</span
                ><small>{{
                  widgetStates[widget.id].message || "组件数据不可用"
                }}</small>
              </div>
              <div
                v-else-if="
                  [
                    'line-chart',
                    'bar-chart',
                    'custom-chart',
                    'pie-chart',
                    'ring-chart',
                    'gauge',
                    'progress',
                    'funnel',
                    'radar',
                    'scatter',
                    'area-chart',
                    'pictorial-chart',
                    'treemap-chart',
                    'calendar-chart',
                    'bar3d-chart',
                  ].includes(widget.type)
                "
                class="chart-host"
                @click.stop
              >
                <div class="chart-box" :data-chart-widget-id="widget.id"></div>
                <div
                  v-if="widgetStates[widget.id]?.quality === 'NO_DATA'"
                  class="chart-empty-overlay"
                >
                  暂无数据
                </div>
              </div>
              <div
                v-else-if="widget.type === 'word-cloud'"
                class="word-cloud-box"
                @click.stop
              >
                <button
                  v-for="(item, index) in wordCloudItems(widget)"
                  :key="`${item.label}-${index}`"
                  type="button"
                  :style="{ fontSize: `${item.size}px`, color: item.color }"
                  @click.stop="handleWidgetClick(widget, item.row)"
                >
                  {{ item.label }}
                </button>
              </div>
              <div
                v-else-if="
                  widget.type === 'metric-card' || widget.type === 'number-flip'
                "
                class="metric-value"
                :class="{
                  'number-flip-content': widget.type === 'number-flip',
                  'split-digits':
                    widget.type === 'number-flip' &&
                    widget.style?.flipSplitDigits === true,
                }"
              >
                <span
                  v-if="
                    widget.type !== 'number-flip' ||
                    widget.style?.flipSplitDigits !== true
                  "
                  :class="{ 'flip-value': widget.type === 'number-flip' }"
                  >{{ metricValue(widget) }}</span
                ><span v-else class="flip-cells"
                  ><b
                    v-for="(character, index) in numberFlipCharacters(widget)"
                    :key="`${widget.id}-${index}`"
                    >{{ character }}</b
                  ></span
                ><small>{{ widget.style?.unit || "" }}</small>
              </div>
              <div
                v-else-if="widget.type === 'statistics'"
                class="statistics-content"
                :class="[
                  `stats-${widget.style?.statsMode || 'card'}`,
                  `stats-layout-${widget.style?.statsLayout || 'vertical'}`,
                  {
                    'stats-label-after':
                      widget.style?.statsLabelPosition === 'after',
                  },
                ]"
                :style="{
                  '--stats-up': widget.style?.statsUpColor || '#35d4b0',
                  '--stats-down': widget.style?.statsDownColor || '#ef8d8d',
                }"
              >
                <div class="statistics-main">
                  <span v-if="dashboardStatisticsBodyTitleVisible(widget, statisticsHeadingTitle(widget))" class="statistics-label">{{
                    statisticsValue(
                      widget,
                      "label",
                      dashboardTitleText(widget.style?.title),
                    )
                  }}</span
                  ><strong
                    :style="{
                      fontSize: `${Number(widget.style?.statsValueSize) || 34}px`,
                    }"
                    >{{ widget.style?.statsPrefix || ""
                    }}{{ statisticsValue(widget, "value", "—")
                    }}<small>{{
                      statisticsValue(
                        widget,
                        "suffix",
                        widget.style?.unit || "",
                      )
                    }}</small></strong
                  >
                </div>
                <div
                  v-if="widget.style?.statsShowCompare !== false"
                  class="statistics-compare"
                  :class="statisticsCompareClass(widget)"
                >
                  <span>{{
                    statisticsValue(widget, "compareLabel", "环比")
                  }}</span
                  ><b>{{ statisticsCompareText(widget) }}</b>
                </div>
              </div>
              <div
                v-else-if="widget.type === 'access-list'"
                class="access-list-content"
                :class="`access-${widget.style?.accessVariant || 'person'}`"
              >
                <div
                  v-for="(row, index) in accessListRows(widget)"
                  :key="row.id || index"
                  class="access-list-row"
                  @click.stop="handleWidgetClick(widget, row)"
                >
                  <DashboardMediaImage
                    v-if="widget.style?.accessShowAvatar !== false && accessListValue(widget, row, 'avatar')"
                    class="access-avatar"
                    :value="accessListValue(widget, row, 'avatar')"
                    :context="mediaContext(widget)"
                    @expired="loadWidget(widget)"
                  />
                  <div class="access-copy">
                    <div class="access-primary">
                      <strong>{{
                        accessListValue(widget, row, "title") || "—"
                      }}</strong
                      ><span>{{
                        accessListValue(widget, row, "subtitle")
                      }}</span
                      ><time>{{
                        accessListValue(widget, row, "time")
                      }}</time>
                    </div>
                    <div class="access-company">
                      {{ accessListValue(widget, row, "company") || "—" }}
                    </div>
                  </div>
                  <b
                    class="access-status"
                    :class="{
                      success: accessStatusSuccess(widget, row),
                      neutral: !accessStatusSuccess(widget, row),
                    }"
                    >{{ accessStatusText(widget, row) }}</b
                  >
                </div>
                <div v-if="!accessListRows(widget).length" class="empty-hint">
                  暂无进出场记录
                </div>
              </div>
              <DashboardCarousel
                v-else-if="widget.type === 'carousel'"
                class="carousel-content"
                :rows="carouselRows(widget)"
                :columns="columns(widget)"
                :options="widget.style || {}"
                :format-value="formatValue"
                @select="handleWidgetClick(widget, $event)"
              />
              <DashboardRingText
                v-else-if="widget.type === 'ring-text'"
                class="ring-text-content"
                :items="ringTextItems(widget)"
                :options="widget.style || {}"
                :center-text="String(statisticsValue(widget, 'value', '工程驾驶舱'))"
              />
              <DashboardGantt
                v-else-if="widget.type === 'gantt-chart'"
                :rows="widgetRows(widget)"
                :field-map="widget.binding?.fieldMap || {}"
                :options="widget.style || {}"
                @select="(row) => handleWidgetClick(widget, row)"
              />
              <div
                v-else-if="widget.type === 'milestone-timeline'"
                class="milestone-timeline-content"
                :class="{
                  'labels-above':
                    widget.style?.timelineLabelPosition === 'above',
                  'layout-spread': widget.style?.timelineLayout === 'spread',
                }"
              >
                <div
                  v-for="(item, index) in milestoneRows(widget)"
                  :key="item.id || index"
                  class="milestone-node"
                  :class="{ done: item.done, active: item.active }"
                  @click.stop="handleWidgetClick(widget, item.row)"
                >
                  <i></i><b>{{ item.label }}</b
                  ><span v-if="widget.style?.timelineShowDates !== false">{{
                    item.startDate || "—"
                  }}</span
                  ><small v-if="widget.style?.timelineShowEndDate === true">{{
                    item.endDate || "—"
                  }}</small>
                </div>
                <div v-if="!milestoneRows(widget).length" class="empty-hint">
                  暂无里程碑数据
                </div>
              </div>
              <div
                v-else-if="widget.type === 'current-time'"
                class="clock-content"
              >
                <el-icon v-if="widget.style?.clockShowIcon !== false"
                  ><Clock /></el-icon
                ><strong>{{ currentTimeText(widget) }}</strong>
              </div>
              <div
                v-else-if="widget.type === 'weather'"
                class="weather-content"
              >
                <div class="weather-symbol">
                  <el-icon><Cloudy /></el-icon>
                </div>
                <div>
                  <strong
                    >{{ weatherValue(widget, "temperature", "—")
                    }}{{ widget.style?.temperatureUnit || "℃" }}</strong
                  ><span>{{
                    weatherValue(widget, "condition", "待接入天气数据")
                  }}</span>
                </div>
                <small>{{ weatherValue(widget, "city", "未设置城市") }}</small>
              </div>
              <div
                v-else-if="widget.type === 'color-block'"
                class="color-block-content"
              >
                <span
                  class="color-block-swatch"
                  :style="{ background: colorBlockValue(widget) }"
                ></span
                ><strong>{{ colorBlockLabel(widget) }}</strong>
              </div>
              <div
                v-else-if="
                  widget.type === 'text' || widget.type === 'rich-text'
                "
                class="text-content"
              >
                {{ widget.style?.text || widget.style?.title || "" }}
              </div>
              <div v-else-if="widget.type === 'icon'" class="icon-content">
                <DashboardIcon :options="widget.style" :name="widget.name || '图标'" />
              </div>
              <div v-else-if="widget.type === 'button'" class="button-content">
                <button type="button" :aria-label="dashboardButtonLabel(widget.style) || widget.name || widget.style?.title || '按钮'" :style="dashboardButtonStyle(widget.style, dashboardResourceUrl(widget.style?.titleImageRef))" @click.stop="handleWidgetClick(widget)">
                  {{ dashboardButtonLabel(widget.style) }}
                </button>
              </div>
              <DashboardTabs
                v-else-if="widget.type === 'tabs'"
                class="tabs-content"
                :tabs="tabItems(widget)"
                :model-value="tabIndex(widget)"
                :fallback="widget.style?.tabContent || ''"
                @update:model-value="selectTab(widget, $event)"
              />
              <DashboardFilterForm
                v-else-if="['filter-form', 'designer-form', 'online-form'].includes(widget.type)"
                v-model="formValues[widget.id]"
                class="filter-form-content"
                :fields="formFields(widget)"
                @submit="submitFilterForm(widget)"
              />
              <div v-else-if="widget.type === 'image'" class="image-content">
                <img
                  v-if="widget.style?.imageRef"
                  :src="dashboardResourceUrl(widget.style.imageRef)"
                  alt=""
                /><template v-else
                  ><el-icon><Picture /></el-icon
                  ><span>平台图片资源</span></template
                >
              </div>
              <div v-else-if="widget.type === 'video'" class="video-content">
                <video
                  :key="`${widget.id}-${widget.style?.videoRef}-${widget.style?.autoplay}`"
                  v-if="widget.style?.videoRef"
                  :src="dashboardResourceUrl(widget.style.videoRef)"
                  :poster="
                    dashboardResourceUrl(widget.style?.posterRef) || undefined
                  "
                  :autoplay="widget.style?.autoplay === true"
                  @loadeddata="dashboardVideoAutoplay($event, widget.style)"
                  :muted="widget.style?.muted !== false"
                  :loop="widget.style?.loop !== false"
                  :controls="widget.style?.controls !== false"
                  playsinline
                  preload="metadata"
                  @error="markMediaError(widget)"
                ></video>
                <div v-else class="media-placeholder">
                  <el-icon><VideoCamera /></el-icon
                  ><span>请配置平台视频资源</span>
                </div>
              </div>
              <div v-else-if="widget.type === 'iframe'" class="iframe-content">
                <DashboardEmbeddedPage :src="widget.style?.iframeRef" />
              </div>
              <div
                v-else-if="widget.type === 'custom-html'"
                class="custom-html-content"
              >
                <iframe
                  :ref="(el) => setCustomFrameRef(widget.id, el)"
                  :data-custom-html-widget="widget.id"
                  :data-custom-html-nonce="customFrameNonce(widget.id)"
                  :srcdoc="customHtmlSrcdoc(widget)"
                  sandbox="allow-scripts"
                  referrerpolicy="no-referrer"
                  title="自定义内容组件"
                  @load="customFrameLoaded(widget)"
                ></iframe>
                <div
                  v-if="customLoading[widget.id]"
                  class="custom-loading-mask"
                >
                  <span>加载中…</span>
                </div>
              </div>
              <div
                v-else-if="
                  [
                    'map-chart',
                    'map-flow',
                    'map-bar',
                    'map-heat',
                    'map-ranking',
                    'map-timeline',
                  ].includes(widget.type)
                "
                class="map-content"
              >
                <div v-if="widget.style?.mapRef" class="map-render-wrap">
                  <div
                    class="map-chart-box"
                    :data-map-widget-id="widget.id"
                    @click.stop
                  ></div>
                  <small
                    v-if="mapStates[widget.id]?.message"
                    class="map-status"
                    >{{ mapStates[widget.id].message }}</small
                  >
                </div>
                <div v-else class="map-grid">
                  <span
                    v-for="(point, index) in fallbackMapPoints(widget)"
                    :key="`${point.label}-${index}`"
                    class="map-point"
                    :style="{
                      left: `${point.x}%`,
                      top: `${point.y}%`,
                      '--point-color': point.color,
                    }"
                    :title="`${point.label}: ${point.value}`"
                    @click.stop="handleWidgetClick(widget, point.row)"
                    ><i></i><b>{{ point.label }}</b></span
                  ><small class="map-empty">请配置平台 GeoJSON 资源</small>
                </div>
              </div>
              <template v-else-if="widget.type === 'border'"></template>
              <div
                v-else-if="widget.type === 'decoration'"
                class="decoration-content"
              ></div>
              <div v-else class="data-content">
                <div
                  v-if="widgetStates[widget.id]?.quality === 'NO_DATA'"
                  class="empty-hint"
                >
                  暂无数据
                </div>
                <template v-else
                  ><div
                    v-if="widget.type === 'advanced-table'"
                    class="advanced-table-wrap"
                    :class="{
                      stripe: widget.style?.advancedStripe !== false,
                      scroll: widget.style?.advancedScroll !== false,
                    }"
                    :style="{
                      '--advanced-row-height': `${Number(widget.style?.advancedRowHeight) || 34}px`,
                    }"
                  >
                    <table class="runtime-table">
                      <thead v-if="widget.style?.advancedShowHeader !== false">
                        <tr>
                          <th v-if="widget.style?.advancedShowIndex">#</th>
                          <th
                            v-for="(column, columnIndex) in columns(widget)"
                            :key="column.name"
                            :class="{
                              'table-primary-column': columnIndex === 0,
                            }"
                          >
                            {{ column.title || column.name }}
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr
                          v-for="(row, index) in advancedDisplayRows(widget)"
                          :key="row.id || index"
                          @click.stop="handleWidgetClick(widget, row)"
                        >
                          <td v-if="widget.style?.advancedShowIndex">
                            {{ index + 1 }}
                          </td>
                          <td
                            v-for="(column, columnIndex) in columns(widget)"
                            :key="column.name"
                            :class="{
                              'table-primary-column': columnIndex === 0,
                            }"
                          >
                            <span :style="dashboardTableCellStyle(widget.style?.tableRules, column.name, row[column.name])">{{ formatValue(row[column.name], column) }}{{ dashboardTableSuffix(widget.style?.tableRules, column.name, row[column.name]) }}</span>
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                  <DashboardDataTable
                    v-else-if="widget.type === 'table'"
                    :rows="displayRows(widget)" :columns="columns(widget)"
                    :auto-scroll="widget.style?.tableAutoScroll === true"
                    :seconds-per-row="widget.style?.tableScrollSeconds"
                    :sort-state="sortState[widget.id]" :format-value="formatValue"
                    @sort="column => sortTable(widget, column)"
                    @row-click="row => handleWidgetClick(widget, row)"
                  />
                  <div
                    v-else-if="
                      [
                        'rank-table',
                        'carousel-table',
                        'alert-list',
                        'realtime-list',
                      ].includes(widget.type)
                    "
                    class="table-scroll-shell"
                  >
                  <table class="runtime-table">
                    <thead>
                      <tr>
                        <th
                          v-for="column in columns(widget)"
                          :key="column.name"
                          :class="{ sortable: column.sortable }"
                          @click.stop="
                            column.sortable && sortTable(widget, column)
                          "
                        >
                          {{ column.title || column.name
                          }}<span
                            v-if="sortState[widget.id]?.field === column.name"
                            class="sort-mark"
                            >{{
                              sortState[widget.id].direction === "asc"
                                ? "↑"
                                : "↓"
                            }}</span
                          >
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="(row, index) in displayRows(widget)"
                        :key="row.id || index"
                        @click.stop="handleWidgetClick(widget, row)"
                      >
                        <td
                          v-for="column in columns(widget)"
                          :key="column.name"
                        >
                          {{ formatValue(row[column.name], column) }}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                  </div>
                  <div v-else class="generic-widget">
                    {{ dashboardTitleText(widget.style?.text) }}
                  </div></template
                >
              </div>
              <div v-if="widgetStates[widget.id]?.stale" class="stale-label">
                <el-icon><Warning /></el-icon>{{ widgetStates[widget.id]?.refreshError ? '更新失败，保留上次数据' : '最近可信数据' }} ·
                {{
                  formatDashboardDateTime(
                    widgetStates[widget.id]?.fetchedAt,
                    "",
                  )
                }}
              </div>
              <div
                v-if="
                  widgetStates[widget.id]?.businessTime ||
                  widgetStates[widget.id]?.eventTime
                "
                class="business-time"
              >
                业务时间
                {{
                  formatTime(
                    widgetStates[widget.id]?.businessTime ||
                      widgetStates[widget.id]?.eventTime,
                  )
                }}
              </div>
            </div>
          </div>
        </div>
        <div class="stage-scale">{{ Math.round(stageScale * 100) }}%</div>
        <button
          v-if="activeDrilldownWidget"
          type="button"
          class="drilldown-back-global"
          @click.stop="drilldownBack(activeDrilldownWidget)"
        >
          返回上级
        </button>
        <div
          class="fullscreen-hover-zone"
          :style="fullscreenButtonStyle"
          :aria-label="isFullscreen ? '退出全屏' : '全屏显示'"
        >
          <button
            type="button"
            class="fullscreen-enter"
            :title="isFullscreen ? '退出全屏' : '全屏显示'"
            @click.stop="toggleFullscreen"
          >
            <el-icon><FullScreen /></el-icon>
          </button>
        </div>
      </div>
    </div>
    <el-dialog
      v-model="detailPopup.open"
      :title="detailPopup.title || '数据详情'"
      width="560px"
      class="dashboard-detail-dialog"
      destroy-on-close
    >
      <el-table
        v-if="detailPopup.widget && detailPopup.row"
        :data="detailRows"
        border
      >
        <el-table-column prop="label" label="字段" width="170" />
        <el-table-column prop="value" label="值" show-overflow-tooltip />
      </el-table>
      <el-empty v-else description="暂无详情数据" />
    </el-dialog>
  </div>
</template>

<script setup>
import { dashboardRefreshError, mergeDashboardRefreshResult, runDashboardRefreshBatch } from "@/utils/dashboardRefresh";
import DashboardMediaImage from "@/components/DashboardMediaImage/index.vue";
import DashboardDataTable from "@/components/DashboardDataTable/index.vue";
import DashboardEmbeddedPage from "@/components/DashboardEmbeddedPage/index.vue";
import { resolveDashboardRuntimeTarget } from "@/utils/dashboardMenuRoute";
import { dashboardTableCellStyle, dashboardTableSuffix } from "@/utils/dashboardTableRules";
import { dashboardVideoAutoplay, dashboardColorBlockValue, dashboardButtonStyle, dashboardButtonUsesImage, dashboardButtonLabel, dashboardTitleText, dashboardWidgetTitleVisible, dashboardStatisticsBodyTitleVisible, dashboardStatisticsField, normalizeDashboardStatisticsStyle, dashboardFormOptions, dashboardCarouselRows, dashboardHeadingHeight } from "@/utils/dashboardPresentation";
import { dashboardComponentCapabilities } from "@/utils/dashboardComponentCapabilities";
import { formatDashboardMetricValue, formatDashboardMetricDisplay } from "@/utils/dashboardMetric";
import { mergeDashboardChartOption as mergeSafeOption, applyDashboardChartProperties } from "@/utils/dashboardChartOptions";
import DashboardGantt from "@/components/DashboardGantt/index.vue";
import { buildDashboardMapOption, dashboardMapData } from "@/utils/dashboardMapOptions";
import DashboardRingText from "@/components/DashboardRingText/index.vue";
import DashboardFilterForm from "@/components/DashboardFilterForm/index.vue";
import DashboardTabs from "@/components/DashboardTabs/index.vue";
import DashboardCarousel from "@/components/DashboardCarousel/index.vue";
import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import * as echarts from "echarts";
import { ElMessage } from "element-plus";
import {
  Back,
  Calendar,
  Clock,
  Cloudy,
  Edit,
  FullScreen,
  MapLocation,
  Monitor,
  Picture,
  Refresh,
  Setting,
  VideoCamera,
  Warning,
  WarningFilled,
} from "@element-plus/icons-vue";
import {
  fetchDashboardData,
  fetchDashboardShareData,
  fetchDashboardSharePageData,
  getDashboardRevisionPreview,
  getDashboardRuntime,
  getDashboardRuntimeByCode,
  getDashboardShareRuntime,
  getDashboardShareVersion,
  listDashboardDatasets,
} from "@/api/dashboard";
import { getDicts } from "@/api/system/dict/data";
import { getToken } from "@/utils/auth";
import DashboardIcon from "@/components/DashboardIcon/index.vue";
import {
  dashboardBackgroundLayout,
  dashboardResourceUrl,
  formatDashboardDate,
  formatDashboardDateTime,
  formatDashboardFieldValue,
} from "@/utils/dashboard";
import useTagsViewStore from "@/store/modules/tagsView";
import {
  calculateFullscreenScale,
  normalizeFullscreenScaleMode,
} from "@/utils/dashboardViewport";

const route = useRoute();
const router = useRouter();
let pageId = Number(route.params.pageId) || 0;
const routePageCode = computed(() => {
  const menuPageCode = String(route.query?.menuPageCode || "").trim();
  const runtimePageCode = String(route.query?.runtimePageCode || "").trim();
  if (route.meta?.dashboardRuntime && isSafePageCode(menuPageCode))
    return menuPageCode;
  if (String(route.path || "").startsWith("/dashboard/runtime/") && isSafePageCode(runtimePageCode))
    return runtimePageCode;
  return resolveDashboardRuntimeTarget(route).pageCode;
});
const isPreview = computed(
  () =>
    String(route.query.preview || "") === "1" ||
    String(route.query.preview || "") === "true",
);
const isFullscreenPreview = computed(
  () =>
    isPreview.value &&
    ["1", "true"].includes(String(route.query.fullscreen || "").toLowerCase()),
);
const revisionId = computed(() => Number(route.query.revisionId) || 0);
const isEmbedMode = computed(() =>
  ["1", "true"].includes(String(route.query.embedMode || "").toLowerCase()),
);
const shareToken = computed(() => {
  const routeToken = String(route.params.token || "").trim();
  if (routeToken) return routeToken;
  // 仅在分享路径上兼容 query token。普通运行路由的 query 可能把
  // “token”作为业务参数，不能因此误切换到匿名分享模式。
  return String(route.path || "").startsWith("/dashboard/share/")
    ? String(route.query.token || "").trim()
    : "";
});
const isShareMode = computed(() => shareToken.value.length > 0);
// 只有后台菜单路由带有此标记；页面管理的运行/预览路由保留编辑工具栏。
const isMenuRuntime = computed(() =>
  (route.meta?.dashboardRuntime === true ||
    String(route.query?.menuRuntime || "") === "1") &&
  !isEmbedMode.value &&
  !isShareMode.value,
);
const channel = computed(() => String(route.query.channel || ""));
const BRIDGE_PROTOCOL = "bigscreen-bridge";
const BRIDGE_VERSION = 1;
const CUSTOM_HTML_PROTOCOL = "dashboard-custom-html";
const CUSTOM_HTML_VERSION = 1;
// AppMain 为运行路由保留稳定组件 key，切页只更新配置，不移除原生全屏元素。

const loading = ref(true);
const refreshing = ref(false);
const refreshNotice = ref("");
const runtimeRequestOptions = { dashboardRuntimeRequest: true };
const widgetRequests = new Map();
const widgetResultKeys = new Map();
const socketRequestKeys = new Map();
const socketRefreshTimers = new Map();
let pageRefreshTask;
let loadedConfigurationKey = "";
let loadedRevisionId = 0;
let needsConfigurationCheck = false;
const SHARE_VERSION_POLL_MS = 30000;
let shareVersionTimer = null;
let shareVersionTask = null;
let shareVersionEpoch = 0;
let runtimeActive = true;
const error = ref("");
const schema = ref(null);
const runtime = reactive({
  pageName: "",
  versionNo: "",
  preview: false,
  previewSource: "",
  versionMode: "FIXED",
});
const widgetStates = reactive({});
const datasetCatalog = ref([]);
const runtimeDatasetsLoaded = ref(false);
const dictionaryCache = reactive({});
const dictionaryRequests = new Map();
const linkedFilters = reactive({});
const drilldownStates = reactive({});
const pageFilterValues = reactive({});
const chartElements = {};
const chartInstances = {};
const mapElements = {};
const mapInstances = {};
const mapStates = reactive({});
const mapGeoJsonCache = new Map();
const mapRequests = new Map();
const chartRenderSignatures = new Map();
const mapRenderSignatures = new Map();
const mapRefreshRequests = new Map();
const mapRegistrations = new Map();
const wsConnections = {};
const wsReconnectTimers = {};
const wsReconnectAttempts = {};
const widgetRefreshTimers = {};
const overrideData = reactive({});
const customFrameRefs = {};
const customFrameWindows = {};
const customFrameNonces = {};
const customLoading = reactive({});
const customLoadingTimers = {};
let customRequestSequence = 0;
const formValues = reactive({});
const tabValues = reactive({});
const sortState = reactive({});
const clockNow = ref(new Date());
const lastLoaded = ref("");
const activeWidgetId = ref("");
const runtimePage = ref();
const isFullscreen = ref(false);
const fullscreenFallback = ref(false);
const detailPopup = reactive({
  open: false,
  title: "",
  widget: null,
  row: null,
});
const detailRows = computed(() => {
  const widget = detailPopup.widget;
  const row = detailPopup.row;
  if (!widget || !row || typeof row !== "object") return [];
  return columns(widget).map((column) => ({
    label: column.title || column.name,
    value: formatValue(row[column.name], column),
  }));
});
const activeDrilldownWidget = computed(
  () =>
    visibleWidgets.value.find(
      (widget) =>
        widget.id === activeWidgetId.value && drilldownState(widget).level > 0,
    ) || null,
);
const stageViewport = ref();
const stageElement = ref();
const stageScale = ref(0.8);
const stageScaleX = ref(0.8);
const stageScaleY = ref(0.8);
const showGrid = ref(false);
let refreshTimer;
let loadGeneration = 0;
let requestGeneration = 0;
let clockTimer;
let stageResizeObserver;
let observedStageViewport;

const typeLabels = {
  "metric-card": "指标卡片",
  "number-flip": "数字翻牌",
  statistics: "统计概览",
  "line-chart": "折线图",
  "bar-chart": "柱状图",
  "custom-chart": "通用图表",
  "pie-chart": "饼图",
  "ring-chart": "环形图",
  gauge: "仪表盘",
  progress: "进度图",
  funnel: "漏斗图",
  radar: "雷达图",
  scatter: "散点图",
  "area-chart": "面积图",
  "pictorial-chart": "象形图",
  "treemap-chart": "矩形树图",
  "calendar-chart": "日历热力图",
  "bar3d-chart": "3D 柱形图",
  "word-cloud": "文字云",
  table: "数据表格",
  "advanced-table": "高级表格",
  "access-list": "图文记录列表",
  "rank-table": "排名表",
  "carousel-table": "轮播表格",
  carousel: "卡片轮播",
  "alert-list": "告警列表",
  "realtime-list": "实时列表",
  "filter-form": "查询表单",
  "designer-form": "设计器表单",
  "online-form": "在线表单（查询）",
  tabs: "选项卡",
  text: "文本",
  "rich-text": "富文本",
  icon: "图标",
  button: "按钮",
  image: "图片",
  border: "边框",
  decoration: "装饰线",
  "current-time": "当前时间",
  weather: "天气预报",
  video: "视频",
  iframe: "内嵌页面",
  "custom-html": "自定义内容",
  "color-block": "颜色块",
  "ring-text": "轨道环形文字",
  "map-chart": "受控地图",
  "map-flow": "飞线地图",
  "map-bar": "柱形地图",
  "map-heat": "热力地图",
  "map-ranking": "柱形排名地图",
  "map-timeline": "时间轴飞线",
};
typeLabels["milestone-timeline"] = "里程碑时间轴";
typeLabels["gantt-chart"] = "计划甘特图";
const customChartTypes = new Set([
  "line-chart",
  "bar-chart",
  "area-chart",
  "pie-chart",
  "ring-chart",
  "gauge",
  "progress",
  "funnel",
  "radar",
  "scatter",
]);
const palettePresets = {
  teal: ["#35d4b0", "#5b8ff9", "#e7ab47", "#c678dd", "#ef8d8d", "#53b7d6"],
  ocean: ["#4cc9f0", "#4361ee", "#4895ef", "#72efdd", "#bde0fe", "#90be6d"],
  amber: ["#f6bd16", "#f08a24", "#e86850", "#5b8ff9", "#61ddaa", "#9270ca"],
  purple: ["#9270ca", "#b37feb", "#5b8ff9", "#61ddaa", "#f6bd16", "#e86850"],
  mono: ["#d9e2ec", "#9fb3c8", "#627d98", "#486581", "#334e68", "#bcccdc"],
};
const visibleWidgets = computed(() => {
  const widgets = (schema.value?.widgets || []).filter(
    (widget) => widget.state?.visible !== false,
  );
  const tabWidgets = widgets.filter((widget) => widget.type === "tabs");
  if (!tabWidgets.length) return widgets;
  const assigned = new Set();
  const active = new Set();
  tabWidgets.forEach((tabWidget) => {
    tabItems(tabWidget).forEach((item) =>
      (Array.isArray(item.widgetIds) ? item.widgetIds : []).forEach((id) =>
        assigned.add(id),
      ),
    );
    const current = tabItems(tabWidget)[tabIndex(tabWidget)];
    (Array.isArray(current?.widgetIds) ? current.widgetIds : []).forEach((id) =>
      active.add(id),
    );
  });
  return widgets.filter(
    (widget) =>
      widget.type === "tabs" ||
      !assigned.has(widget.id) ||
      active.has(widget.id),
  );
});
const pageFilters = computed(() =>
  Array.isArray(schema.value?.filters) ? schema.value.filters : [],
);
const canvasWidth = computed(() => Number(schema.value?.canvas?.width) || 1920);
const canvasHeight = computed(
  () => Number(schema.value?.canvas?.height) || 1080,
);
const stageHolderStyle = computed(() => ({
  width: `${canvasWidth.value * stageScaleX.value}px`,
  height: `${canvasHeight.value * stageScaleY.value}px`,
}));
const fullscreenButtonStyle = computed(() => {
  const scaledWidth = canvasWidth.value * stageScaleX.value;
  const top =
    isEmbedMode.value || isShareMode.value || isFullscreen.value ? 12 : 34;
  return {
    left: `clamp(12px, calc(50% + ${scaledWidth / 2}px - 50px), calc(100% - 50px))`,
    top: `${top}px`,
  };
});
const stageStyle = computed(() => {
  const canvas = schema.value?.canvas || {};
  const background = canvas.background || {};
  return {
    width: `${canvasWidth.value}px`,
    height: `${canvasHeight.value}px`,
    transform: `scale(${stageScaleX.value}, ${stageScaleY.value})`,
    backgroundColor: background.color || canvas.backgroundColor || "#0b1220",
    backgroundImage: background.imageRef
      ? `url("${dashboardResourceUrl(background.imageRef)}")`
      : undefined,
    ...dashboardBackgroundLayout(background),
  };
});
const stageViewportStyle = computed(() => {
  const canvas = schema.value?.canvas || {};
  const background = canvas.background || {};
  return {
    backgroundColor:
      background.color || canvas.backgroundColor ||
      (canvas.theme === "light" ? "#f2f5f8" : "#0b1220"),
  };
});
const runtimeWatermarkStyle = computed(() => ({
  fontSize: `${Number(schema.value?.canvas?.watermark?.fontSize) || 22}px`,
  color: schema.value?.canvas?.watermark?.color || "#d4deeb",
  transform: `rotate(${Number(schema.value?.canvas?.watermark?.rotate) || 0}deg)`,
  opacity: Number(schema.value?.canvas?.watermark?.opacity ?? 0.12),
}));

function syncPageIdFromRoute() {
  // Reset the prior page ID on every navigation. Persisted menu metadata is
  // independent of parent directory and cannot be overridden by business query.
  pageId = resolveDashboardRuntimeTarget(route).pageId;
}

function requestRuntimeConfiguration() {
  return isShareMode.value
    ? getDashboardShareRuntime(shareToken.value, routePageCode.value, runtimeRequestOptions)
    : revisionId.value && pageId
      ? getDashboardRevisionPreview(pageId, revisionId.value, runtimeRequestOptions)
      : routePageCode.value
        ? getDashboardRuntimeByCode(routePageCode.value, isPreview.value, runtimeRequestOptions)
        : getDashboardRuntime(pageId, isPreview.value, runtimeRequestOptions);
}
function runtimeConfigurationKey(data) {
  return JSON.stringify([data.revisionId, data.pageName, data.schema, data.datasets]);
}
function load(configuration) {
  stopShareVersionCheck();
  const requestId = ++requestGeneration;
  const entryRoute = { path: route.path, params: { ...route.params }, query: { ...route.query }, meta: { ...route.meta } };
  syncPageIdFromRoute();
  loading.value = true;
  refreshing.value = false;
  refreshNotice.value = "";
  pageRefreshTask = undefined;
  widgetRequests.clear();
  widgetResultKeys.clear();
  needsConfigurationCheck = false;
  error.value = "";
  closeSockets();
  disposeCharts();
  clearRefreshTimer();
  // 路由切换时先撤销上一页面的独立刷新/轮播定时器和沙箱帧，避免旧组件
  // 在新页面请求期间继续发起取数或把结果写回当前画布。
  resetWidgetRefreshTimers([]);
  resetCarouselTimers([]);
  closeCustomFrames();
  loadGeneration += 1;
  schema.value = null;
  const runtimeRequest = configuration?.schema
    ? Promise.resolve({ data: configuration }) : requestRuntimeConfiguration();
  return runtimeRequest
    .then((res) => {
      if (requestId !== requestGeneration) return undefined;
      const data = res.data || {};
      loadedConfigurationKey = runtimeConfigurationKey(data);
      loadedRevisionId = Number(data.revisionId) || 0;
      pageId = Number(data.pageId) || pageId;
      runtime.pageName = data.pageName || "";
      updateRuntimePageTag(data, entryRoute, requestId);
      runtime.versionNo = data.versionNo || "";
      runtime.versionMode = data.versionMode === 'FOLLOW_PUBLISHED' ? 'FOLLOW_PUBLISHED' : 'FIXED';
      runtime.preview = Boolean(data.preview);
      runtime.previewSource =
        data.previewSource || (data.preview ? "DRAFT" : "");
      schema.value = normalizeSchema(data.schema);
      runtimeDatasetsLoaded.value = Array.isArray(data.datasets);
      datasetCatalog.value = runtimeDatasetsLoaded.value ? data.datasets : [];
      initPageFilters();
      initWidgetControls(schema.value.widgets || []);
      error.value = "";
      return nextTick(() => {
        if (requestId !== requestGeneration) return undefined;
        fitStage();
        return loadWidgets(schema.value.widgets || []);
      });
    })
    .catch((err) => {
      if (requestId !== requestGeneration) return;
      error.value =
        err?.message ||
        (isPreview.value ? "草稿预览加载失败" : "运行版本读取失败");
    })
    .finally(() => {
      if (requestId !== requestGeneration) return;
      loading.value = false;
      lastLoaded.value = formatDashboardDateTime(new Date());
      scheduleShareVersionCheck();
    });
}

function stopShareVersionCheck() {
  window.clearTimeout(shareVersionTimer);
  shareVersionTimer = null;
  shareVersionEpoch += 1;
  shareVersionTask = null;
}

function scheduleShareVersionCheck() {
  window.clearTimeout(shareVersionTimer);
  shareVersionTimer = null;
  if (!runtimeActive || !isShareMode.value || !schema.value || error.value || document.hidden) return;
  shareVersionTimer = window.setTimeout(checkShareVersion, needsConfigurationCheck ? 0 : SHARE_VERSION_POLL_MS);
}

function checkShareVersion() {
  if (!runtimeActive || !isShareMode.value || !schema.value || loading.value || error.value || document.hidden) return Promise.resolve();
  if (shareVersionTask) return shareVersionTask.promise;
  const epoch = shareVersionEpoch;
  const generation = loadGeneration;
  const token = shareToken.value;
  const code = routePageCode.value;
  const current = () => runtimeActive && epoch === shareVersionEpoch && generation === loadGeneration &&
    token === shareToken.value && code === routePageCode.value && Boolean(schema.value);
  const pending = {};
  shareVersionTask = pending;
  pending.promise = Promise.resolve().then(async () => {
    try {
      if (!current()) return;
      const response = await getDashboardShareVersion(token, code, runtimeRequestOptions);
      if (!current()) return;
      const version = response.data || {};
      if (Number(version.pageId) !== pageId || Number(version.revisionId) !== loadedRevisionId) {
        const latest = await requestRuntimeConfiguration();
        if (!current()) return;
        return await applyRuntimeConfiguration(latest.data || {});
      }
      // 固定/跟随切换但实际版本未变时，只更新策略，不重绘组件。
      runtime.versionMode = version.versionMode === 'FOLLOW_PUBLISHED' ? 'FOLLOW_PUBLISHED' : 'FIXED';
      needsConfigurationCheck = false;
      refreshNotice.value = '';
    } catch (cause) {
      if (!current()) return;
      const failure = dashboardRefreshError(cause);
      if (failure.transient) { needsConfigurationCheck = false; refreshNotice.value = '版本检查暂不可用，保留当前画面并稍后重试'; }
      else invalidateRuntime(failure);
    } finally {
      if (shareVersionTask === pending) shareVersionTask = null;
      if (runtimeActive && epoch === shareVersionEpoch) scheduleShareVersionCheck();
    }
  });
  return pending.promise;
}

function handleShareVisibilityChange() {
  if (document.hidden) stopShareVersionCheck();
  else if (runtimeActive) checkShareVersion();
}

function captureRuntimeViewState() {
  const clone = value => JSON.parse(JSON.stringify(value));
  return {
    filters: pageFilters.value.map(filter => ({ ...filter, value: pageFilterValues[filter.id] })),
    widgets: (schema.value?.widgets || []).map(widget => ({
      id: widget.id, type: widget.type, binding: JSON.stringify(widget.binding),
      fields: clone(formFields(widget)), form: clone(formValues[widget.id] || {}),
      tabKey: tabItems(widget)[tabIndex(widget)]?.key,
      sort: clone(sortState[widget.id] || null), linked: clone(linkedFilters[widget.id] || null),
      drilldown: clone(drilldownStates[widget.id] || null), interaction: JSON.stringify(widget.interaction),
    })),
    left: stageViewport.value?.scrollLeft || 0, top: stageViewport.value?.scrollTop || 0,
  };
}

function restoreRuntimeViewState(state) {
  pageFilters.value.forEach(filter => {
    const previous = state.filters.find(item => item.id === filter.id && item.parameter === filter.parameter &&
      String(item.type || 'STRING').toUpperCase() === String(filter.type || 'STRING').toUpperCase());
    if (previous) pageFilterValues[filter.id] = previous.value;
  });
  (schema.value?.widgets || []).forEach(widget => {
    const previous = state.widgets.find(item => item.id === widget.id && item.type === widget.type);
    if (!previous) return;
    formFields(widget).forEach(field => {
      if (previous.fields.some(item => item.name === field.name && item.parameter === field.parameter && item.type === field.type) &&
          Object.hasOwn(previous.form, field.name)) formValues[widget.id][field.name] = previous.form[field.name];
    });
    const index = tabItems(widget).findIndex(item => item.key != null && item.key === previous.tabKey);
    if (index >= 0) tabValues[widget.id] = index;
    if (previous.binding === JSON.stringify(widget.binding)) {
      if (previous.sort) sortState[widget.id] = previous.sort;
      if (previous.linked) linkedFilters[widget.id] = previous.linked;
      if (previous.drilldown && previous.interaction === JSON.stringify(widget.interaction)) drilldownStates[widget.id] = previous.drilldown;
      else if (previous.drilldown?.level) delete linkedFilters[widget.id];
    }
  });
}

async function applyRuntimeConfiguration(data) {
  if (!isShareMode.value || !schema.value || Number(data.pageId) !== pageId) return load(data);
  const nextSchema = normalizeSchema(data.schema);
  const view = captureRuntimeViewState();
  const previousWidgets = schema.value.widgets || [];
  const widgets = nextSchema.widgets || [];
  const compatible = new Set(widgets.filter(widget => previousWidgets.some(previous =>
    previous.id === widget.id && previous.type === widget.type && JSON.stringify(previous.binding) === JSON.stringify(widget.binding))).map(widget => widget.id));
  const generation = ++loadGeneration;
  ++requestGeneration;
  widgetRequests.clear();
  pageRefreshTask = undefined;
  needsConfigurationCheck = false;
  closeSockets();
  clearRefreshTimer();
  resetWidgetRefreshTimers([]);
  resetCarouselTimers([]);
  closeCustomFrames();
  mapRefreshRequests.clear();
  if (detailPopup.open) Object.assign(detailPopup, { open: false, widget: null, row: null, title: '' });
  for (const key of Object.keys(widgetStates)) if (!compatible.has(key)) { delete widgetStates[key]; widgetResultKeys.delete(key); }
  Object.keys(overrideData).forEach(key => delete overrideData[key]);
  // 换数据绑定的图表立即清掉旧图形，避免新标题下短暂残留旧绑定数据。
  const retained = compatible;
  for (const [instances, elements, signatures] of [[chartInstances, chartElements, chartRenderSignatures], [mapInstances, mapElements, mapRenderSignatures]]) {
    Object.keys(instances).forEach(key => { if (!retained.has(key)) { instances[key].dispose(); delete instances[key]; delete elements[key]; signatures.delete(key); } });
  }
  schema.value = nextSchema;
  loadedConfigurationKey = runtimeConfigurationKey(data);
  loadedRevisionId = Number(data.revisionId) || 0;
  runtime.pageName = data.pageName || '';
  runtime.versionNo = data.versionNo || '';
  runtime.versionMode = data.versionMode === 'FOLLOW_PUBLISHED' ? 'FOLLOW_PUBLISHED' : 'FIXED';
  datasetCatalog.value = Array.isArray(data.datasets) ? data.datasets : [];
  runtimeDatasetsLoaded.value = Array.isArray(data.datasets);
  initPageFilters();
  initWidgetControls(widgets);
  restoreRuntimeViewState(view);
  error.value = '';
  refreshNotice.value = '';
  refreshing.value = true;
  try {
    await nextTick();
    if (generation !== loadGeneration) return;
    fitStage();
    if (stageViewport.value) { stageViewport.value.scrollLeft = view.left; stageViewport.value.scrollTop = view.top; }
    await runDashboardRefreshBatch(widgets, widget => generation === loadGeneration ? loadWidget(widget, generation) : undefined);
    if (generation !== loadGeneration) return;
    await nextTick(() => Promise.all([renderCharts(), loadMapCharts(widgets)]));
    if (generation !== loadGeneration) return;
    resetRefreshTimer();
    resetWidgetRefreshTimers(widgets);
    resetCarouselTimers(widgets);
    lastLoaded.value = formatDashboardDateTime(new Date());
  } catch (cause) {
    if (generation !== loadGeneration) return;
    const failure = dashboardRefreshError(cause);
    if (failure.transient) refreshNotice.value = '更新暂不可用，保留上次显示的数据';
    else invalidateRuntime(failure);
  } finally {
    if (generation === loadGeneration) { refreshing.value = false; scheduleShareVersionCheck(); }
  }
}

function invalidateRuntime(failure) {
  error.value = failure.message;
  Object.assign(detailPopup, { open: false, widget: null, row: null, title: '' });
  stopShareVersionCheck();
  ++loadGeneration;
  ++requestGeneration;
  widgetRequests.clear();
  closeSockets();
  clearRefreshTimer();
  resetWidgetRefreshTimers([]);
  resetCarouselTimers([]);
  closeCustomFrames();
  disposeCharts();
  schema.value = null;
  refreshing.value = false;
}

function updateRuntimePageTag(data, entryRoute, requestId) {
  if (isShareMode.value || isEmbedMode.value || entryRoute.meta?.dashboardRuntime) return;
  const store = useTagsViewStore();
  const entry = resolveDashboardRuntimeTarget(entryRoute);
  const current = store.visitedViews.find(item => item.path === entryRoute.path);
  const mode = data.previewSource === "REVISION"
    ? `历史预览 V${data.versionNo}` : data.preview ? "预览" : "运行";
  const setTitle = (pageName) => {
    if (requestId === requestGeneration)
      store.updateDashboardPageTitle({ path: entryRoute.path, pageName, mode });
  };
  const isEntryPage = entry.pageCode
    ? entry.pageCode === data.pageCode : entry.pageId === Number(data.pageId);
  if (isEntryPage) return setTitle(data.pageName);
  // 画布内切换内容时仍保留入口页面名称，避免把其他已打开的运行页签改名。
  if (current?.meta?.dashboardPageName) return setTitle(current.meta.dashboardPageName);
  if (!entry.pageCode && !entry.pageId) return setTitle(data.pageName);
  const request = entry.pageCode
    ? getDashboardRuntimeByCode(entry.pageCode, isPreview.value)
    : getDashboardRuntime(entry.pageId, isPreview.value);
  request.then(res => setTitle(res.data?.pageName)).catch(() => {
    setTitle(entry.pageCode || `页面 ${entry.pageId}`);
  });
}

function normalizeSchema(raw) {
  const value = typeof raw === "string" ? JSON.parse(raw) : raw || {};
  const canvas = value.canvas || {};
  const paletteName = Object.prototype.hasOwnProperty.call(
    palettePresets,
    canvas.palette,
  )
    ? canvas.palette
    : "teal";
  const paletteColors =
    Array.isArray(canvas.paletteColors) && canvas.paletteColors.length
      ? canvas.paletteColors.slice(0, 12)
      : palettePresets[paletteName];
  return {
    ...value,
    filters: Array.isArray(value.filters)
      ? value.filters.map((filter, index) => ({
          id: filter.id || `page-filter-${index}`,
          label: filter.label || filter.name || "页面过滤器",
          parameter: filter.parameter || filter.param || "",
          type: filter.type || "STRING",
          defaultValue: filter.defaultValue ?? filter.default ?? "",
          targetWidgetIds: Array.isArray(filter.targetWidgetIds)
            ? filter.targetWidgetIds
            : [],
        }))
      : [],
    canvas: {
      width: Number(canvas.width) || 1920,
      height: Number(canvas.height) || 1080,
      ...canvas,
      scaleMode: ["contain", "stretch"].includes(canvas.scaleMode)
        ? canvas.scaleMode
        : "contain",
      fullscreenScaleMode: normalizeFullscreenScaleMode(canvas),
      palette: paletteName,
      paletteColors,
      background: {
        color: canvas.background?.color || canvas.backgroundColor || "#0b1220",
        imageRef: "",
        ...(canvas.background || {}),
      },
      watermark: {
        enabled: false,
        text: "",
        fontSize: 22,
        color: "#d4deeb",
        rotate: -20,
        opacity: 0.12,
        ...(canvas.watermark || {}),
      },
    },
    refresh: {
      enabled: true,
      mode: "interval",
      seconds: 30,
      at: "08:00",
      ...(value.refresh || {}),
      mode: value.refresh?.mode === "daily" ? "daily" : "interval",
      seconds: Math.max(
        5,
        Math.min(3600, Number(value.refresh?.seconds) || 30),
      ),
      at: /^([01]\d|2[0-3]):[0-5]\d$/.test(String(value.refresh?.at || ""))
        ? value.refresh.at
        : "08:00",
    },
    widgets: (value.widgets || [])
      .map((widget, index) => ({
        ...widget,
        layout: {
          x: 0,
          y: 0,
          w: 360,
          h: 180,
          z: index + 1,
          ...(widget.layout || {}),
        },
        state: { visible: true, ...(widget.state || {}) },
        binding: {
          datasetCode: "",
          fieldMap: {},
          parameters: {},
          filters: [],
          displayFields: [],
          rowLimit: 50,
          refreshSeconds: 0,
          ...(widget.binding || {}),
        },
        interaction: {
          onClick: "none",
          target: "",
          targetMode: "route",
          targetPageCode: "",
          targetPageId: null,
          sourceField: "",
          targetParameter: "",
          targetField: "",
          targetWidgetIds: [],
          parameterMappings: [],
          drilldown: { levels: [] },
          ...(widget.interaction || {}),
        },
        style: {
          title: typeLabels[widget.type] || "未识别组件",
          titleVisible: true,
          titleImageEnabled: false,
          titleImageRef: "",
          titleImageFit: "stretch",
          titleImageAlign: "left",
          titleImageHeight: 32,
          subtitle: "",
          titleAlign: "left",
          titleVerticalAlign: "top",
          titleColor: "#9cabbc",
          titleFontSize: 12,
          titleFontWeight: 600,
          subtitleColor: "#aebccc",
          subtitleFontSize: 10,
          subtitleFontWeight: 400,
          color: "#35d4b0",
          customChartType: "bar-chart",
          useSystemPalette: false,
          backgroundColor: "rgba(17,24,39,.84)",
          backgroundTransparent: false,
          borderTransparent: false,
          embeddedMode: false,
          qualityVisible: true,
          opacity: 1,
          borderRadius: 8,
          fontSize: 16,
          fontWeight: 400,
          textAlign: "center",
          contentVerticalAlign: "center",
          contentPaddingTop: null,
          contentPaddingRight: null,
          contentPaddingBottom: null,
          contentPaddingLeft: null,
          borderWidth: 1,
          borderColor: "#35d4b0",
          borderOpacity: 1,
          borderStyle: "solid",
          iconName: "star",
          carouselSeconds: 4,
          carouselInterval: 4,
          carouselDirection: "horizontal",
          carouselShowIndex: false,
          carouselHighlight: false,
          advancedShowHeader: true,
          advancedShowIndex: true,
          advancedStripe: true,
          advancedRowHeight: 34,
          advancedScroll: true,
          tableAutoScroll: false,
          tableScrollSeconds: 5,
          tableHeaderColor: "#8497aa",
          tableTextColor: "#cbd8e6",
          tableValueColor: "#cbd8e6",
          tableHeaderBackground: "transparent",
          tableCellPadding: 6,
          tableFirstColumnWidth: 50,
          statsMode: "card",
          statsLayout: "vertical",
          statsLabelPosition: "before",
          statsShowCompare: true,
          statsUpColor: "#35d4b0",
          statsDownColor: "#ef8d8d",
          statsPrefix: "",
          statsValueSize: 34,
          statsLabelColor: "#9aabbd",
          statsLabelSize: 11,
          statsUnitColor: "#9aabbd",
          statsUnitSize: 12,
          flipMinDigits: 1,
          flipSplitDigits: false,
          flipCellGap: 8,
          flipCellWidth: 42,
          flipCellHeight: 58,
          flipCellBackground: "rgba(28,56,86,.72)",
          flipCellBorderColor: "#35d4b0",
          ringInnerRadius: 46,
          ringOuterRadius: 72,
          ringEqualSegments: false,
          ringLegendShowValue: false,
          ringLegendSuffix: "",
          ringLegendColumns: 1,
          ringLegendWidth: 180,
          ringLegendLeft: 50,
          ringLegendItemGap: 10,
          ringTextRadius: 42,
          ringTextSpeed: 20,
          ringTextDirection: "normal",
          ringTextShowOrbit: true,
          ringTextTilt: 0,
          ringTextGradient: false,
          ringTextCenterText: "",
          timelineShowDates: true,
          timelineShowEndDate: false,
          timelineLabelPosition: "below",
          timelineDateFormat: "YYYY-MM-DD",
          timelineLayout: "compact",
          timelineDoneColor: "#35d4b0",
          timelineActiveColor: "#35d4b0",
          bar3dDepth: 8,
          timeFormat: "HH:mm:ss",
          clockShowIcon: true,
          buttonPaddingX: 22,
          buttonPaddingY: 8,
          imageFit: "contain",
          imagePosition: "center",
          accessVariant: "person",
          accessShowAvatar: true,
          accessAvatarSize: 56,
          accessRowHeight: 72,
          accessStatusSuccessText: "进场",
          accessStatusNeutralText: "出场",
          videoRef: "",
          posterRef: "",
          autoplay: false,
          muted: true,
          loop: true,
          controls: true,
          iframeRef: "",
          mapRef: "",
          temperatureUnit: "℃",
          temperaturePrecision: 0,
          blockColor: "#35d4b0",
          blockLabel: "状态",
          mapShowLabels: false,
          mapLabelColor: "#dbeaf4",
          mapLabelFontSize: 10,
          mapRoam: false,
          mapZoom: 1,
          mapAspectScale: 1,
          mapLayoutX: 50,
          mapLayoutY: 50,
          mapLayoutSize: 96,
          mapAreaColor: "#17344a",
          mapAreaGradient: false,
          mapCenterColor: "#235c6c",
          mapEdgeColor: "#10283d",
          mapBorderColor: "#4c8297",
          mapBorderWidth: 0.8,
          mapEmphasisColor: "#2d6d80",
          mapShadowBlur: 0,
          mapShadowOffsetX: 0,
          mapShadowOffsetY: 0,
          mapShadowColor: "rgba(0,0,0,.35)",
          mapVisualMap: false,
          mapVisualMin: 0,
          mapVisualMax: 100,
          mapVisualMinColor: "#17344a",
          mapVisualMaxColor: "#35d4b0",
          mapPointSize: 9,
          chartConfig: defaultChartConfig(widget.type),
          tabs: [
            {
              key: "overview",
              label: "概览",
              content: "概览内容",
              widgetIds: [],
            },
            {
              key: "detail",
              label: "明细",
              content: "明细内容",
              widgetIds: [],
            },
          ],
          formFields: [
            {
              name: "keyword",
              label: "关键词",
              parameter: "keyword",
              placeholder: "请输入关键词",
            },
          ],
          ...(widget.style || {}),
          chartConfig: normalizeChartConfig(
            widget.type === "custom-chart" ? widget.style?.customChartType || "bar-chart" : widget.type,
            widget.style?.chartConfig,
          ),
        },
      }))
      .map((widget) => {
        const interaction = widget.interaction || {};
        interaction.targetMode =
          interaction.targetMode === "published" ||
          interaction.targetPageCode ||
          interaction.targetPageId
            ? "published"
            : "route";
        interaction.targetPageCode = String(
          interaction.targetPageCode || "",
        ).trim();
        interaction.targetPageId =
          interaction.targetPageId === null ||
          interaction.targetPageId === undefined ||
          interaction.targetPageId === ""
            ? null
            : Number(interaction.targetPageId) || null;
        interaction.targetWidgetIds = Array.isArray(
          interaction.targetWidgetIds,
        )
          ? interaction.targetWidgetIds
          : [];
        widget.interaction = interaction;
        widget.binding.fieldMap =
          widget.binding.fieldMap &&
          typeof widget.binding.fieldMap === "object" &&
          !Array.isArray(widget.binding.fieldMap)
            ? { ...widget.binding.fieldMap }
            : {};
        widget.binding.fieldMap.valueFields = Array.isArray(
          widget.binding.fieldMap.valueFields,
        )
          ? [...new Set(widget.binding.fieldMap.valueFields.filter(Boolean))]
          : [];
        widget.style = normalizeDashboardStatisticsStyle(widget.type, widget.style);
        if (widget.style.title === "自定义 HTML")
          widget.style.title = "自定义内容";
        widget.style.backgroundTransparent =
          widget.style.backgroundTransparent === true;
        widget.style.borderTransparent =
          widget.style.borderTransparent === true;
        widget.style.embeddedMode = widget.style.embeddedMode === true;
        widget.style.titleImageEnabled =
          widget.style.titleImageEnabled === true;
        widget.style.titleImageRef =
          typeof widget.style.titleImageRef === "string"
            ? widget.style.titleImageRef
            : "";
        widget.style.titleImageFit = ["stretch", "contain", "cover"].includes(
          widget.style.titleImageFit,
        )
          ? widget.style.titleImageFit
          : "stretch";
        widget.style.titleImageAlign = ["left", "center", "right"].includes(
          widget.style.titleImageAlign,
        )
          ? widget.style.titleImageAlign
          : "left";
        widget.style.titleImageHeight = Math.max(
          20,
          Math.min(120, Number(widget.style.titleImageHeight) || 32),
        );
        widget.style.titlePaddingTop = Math.max(
          0,
          Math.min(120, Number(widget.style.titlePaddingTop) || 0),
        );
        widget.style.titlePaddingRight = Math.max(
          0,
          Math.min(
            120,
            Number(
              widget.style.titlePaddingRight ??
                (widget.style.titleImageEnabled ? 8 : 0),
            ) || 0,
          ),
        );
        widget.style.titlePaddingBottom = Math.max(
          0,
          Math.min(120, Number(widget.style.titlePaddingBottom) || 0),
        );
        widget.style.titlePaddingLeft = Math.max(
          0,
          Math.min(
            120,
            Number(
              widget.style.titlePaddingLeft ??
                (widget.style.titleImageEnabled ? 8 : 0),
            ) || 0,
          ),
        );
        widget.style.timelineShowDates =
          widget.style.timelineShowDates !== false;
        widget.style.timelineShowEndDate =
          widget.style.timelineShowEndDate === true;
        widget.style.qualityVisible = widget.style.qualityVisible !== false;
        widget.style.clockShowIcon = widget.style.clockShowIcon !== false;
        widget.style.titleVerticalAlign = ["top", "middle", "bottom"].includes(
          widget.style.titleVerticalAlign,
        )
          ? widget.style.titleVerticalAlign
          : "top";
        widget.style.chartConfig.valueScale = [
          "none",
          "thousand",
          "ten-thousand",
          "million",
        ].includes(widget.style.chartConfig.valueScale)
          ? widget.style.chartConfig.valueScale
          : "none";
        widget.style.chartConfig.valuePrecision = normalizePrecision(
          widget.style.chartConfig.valuePrecision,
        );
        widget.style.chartConfig.xAxis.type = safeAxisType(
          widget.style.chartConfig.xAxis.type,
          "category",
        );
        widget.style.chartConfig.xAxis.axisLineShow =
          widget.style.chartConfig.xAxis.axisLineShow !== false;
        widget.style.chartConfig.yAxis.type = safeAxisType(
          widget.style.chartConfig.yAxis.type,
          "value",
        );
        widget.style.chartConfig.yAxis.axisLineShow =
          widget.style.chartConfig.yAxis.axisLineShow !== false;
        ["left", "right", "top", "bottom"].forEach((edge) => {
          widget.style.chartConfig.legend.margin[edge] = safeMargin(
            widget.style.chartConfig.legend.margin[edge],
          );
        });
        return widget;
      }),
  };
}

function defaultChartConfig(type) {
  return {
    tooltip: {
      show: true,
      fontSize: 12,
      textColor: "",
      backgroundColor: "",
      borderColor: "",
    },
    legend: {
      show: false,
      position: "bottom",
      orient: "horizontal",
      fontSize: 12,
      textColor: "",
      itemWidth: 14,
      itemHeight: 8,
      margin: { left: 0, right: 0, top: 0, bottom: 0 },
    },
    xAxis: {
      show: true,
      type: type === "scatter" ? "value" : "category",
      axisLineShow: true,
      labelRotate: 0,
      name: "",
      axisLineColor: "",
      labelColor: "",
      labelFontSize: 11,
      splitLineShow: false,
      splitLineColor: "",
    },
    yAxis: {
      show: true,
      type: "value",
      axisLineShow: true,
      min: null,
      max: null,
      name: "",
      axisLineColor: "",
      labelColor: "",
      labelFontSize: 11,
      splitLineShow: true,
      splitLineColor: "",
    },
    grid: { left: 42, right: 18, top: 24, bottom: 30 },
    smooth: true,
    areaOpacity: type === "area-chart" ? 0.2 : 0.12,
    valueScale: "none",
    valuePrecision: 2,
    stack: false,
    label: {
      show: ["gauge", "progress"].includes(type),
      position: "top",
      format: "{c}",
      color: "",
      fontSize: 11,
      fontWeight: 400,
    },
    markLine: {
      show: false,
      value: null,
      label: "",
      color: "#e7ab47",
      lineType: "dashed",
    },
    center: { x: 50, y: 50 },
    colors: [],
  };
}

function normalizeChartConfig(type, raw) {
  const base = defaultChartConfig(type);
  const value = raw && typeof raw === "object" ? raw : {};
  const colors = Array.isArray(value.colors) ? value.colors : [];
  return {
    ...base,
    ...value,
    tooltip: { ...base.tooltip, ...(value.tooltip || {}) },
    legend: {
      ...base.legend,
      ...(value.legend || {}),
      margin: { ...base.legend.margin, ...(value.legend?.margin || {}) },
    },
    xAxis: { ...base.xAxis, ...(value.xAxis || {}) },
    yAxis: { ...base.yAxis, ...(value.yAxis || {}) },
    grid: { ...base.grid, ...(value.grid || {}) },
    label: { ...base.label, ...(value.label || {}) },
    markLine: { ...base.markLine, ...(value.markLine || {}) },
    center: { ...base.center, ...(value.center || {}) },
    colors,
    colorsText: value.colorsText ?? colors.join(","),
  };
}

function routeFilterValue(filter) {
  const parameter = String(filter?.parameter || "").trim();
  if (!parameter || !Object.prototype.hasOwnProperty.call(route.query, parameter))
    return filter?.defaultValue ?? "";
  const raw = route.query[parameter];
  // 查询参数可能被重复传递；只取第一个值，避免把数组对象直接送入数据集。
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (value === null || value === undefined) return filter?.defaultValue ?? "";
  const text = String(value).trim();
  if (text.length > 128 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(text))
    return filter?.defaultValue ?? "";
  const type = String(filter?.type || "STRING").toUpperCase();
  if (type === "NUMBER" && (text === "" || !Number.isFinite(Number(text))))
    return filter?.defaultValue ?? "";
  if (type === "DATE" && !/^\d{4}-\d{2}-\d{2}$/.test(text))
    return filter?.defaultValue ?? "";
  if (
    type === "DATETIME" &&
    !/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2})?$/.test(text)
  )
    return filter?.defaultValue ?? "";
  return text;
}

function initPageFilters() {
  Object.keys(pageFilterValues).forEach((key) => delete pageFilterValues[key]);
  pageFilters.value.forEach((filter) => {
    pageFilterValues[filter.id] = routeFilterValue(filter);
  });
}

function initWidgetControls(widgets) {
  Object.keys(formValues).forEach((key) => delete formValues[key]);
  Object.keys(tabValues).forEach((key) => delete tabValues[key]);
  Object.keys(sortState).forEach((key) => delete sortState[key]);
  Object.keys(linkedFilters).forEach((key) => delete linkedFilters[key]);
  Object.keys(drilldownStates).forEach((key) => delete drilldownStates[key]);
  Object.keys(carouselOffsets).forEach((key) => delete carouselOffsets[key]);
  Object.keys(advancedOffsets).forEach((key) => delete advancedOffsets[key]);
  widgets.forEach((widget) => {
    formValues[widget.id] = {};
    formFields(widget).forEach((field) => {
      formValues[widget.id][field.name] = field.defaultValue ?? "";
    });
    tabValues[widget.id] = 0;
    drilldownStates[widget.id] = { level: 0, history: [] };
  });
}

function loadWidgets(widgets) {
  const generation = ++loadGeneration;
  closeSockets();
  widgetRequests.clear();
  widgetResultKeys.clear();
  Object.keys(widgetStates).forEach(key => delete widgetStates[key]);
  const catalogRequest = isShareMode.value || runtimeDatasetsLoaded.value
    ? Promise.resolve({ rows: datasetCatalog.value })
    : listDashboardDatasets({ pageNum: 1, pageSize: 1000 }).catch(() => ({ rows: [] }));
  return catalogRequest.then(async datasetRes => {
    if (generation !== loadGeneration) return;
    datasetCatalog.value = datasetRes.rows || [];
    loadDictionaries(datasetCatalog.value);
    await runDashboardRefreshBatch(widgets, widget => loadWidget(widget, generation));
    if (generation !== loadGeneration) return;
    if (needsConfigurationCheck) {
      needsConfigurationCheck = false;
      const latest = await requestRuntimeConfiguration();
      if (generation !== loadGeneration) return;
      if (runtimeConfigurationKey(latest.data || {}) !== loadedConfigurationKey) return applyRuntimeConfiguration(latest.data);
    }
    await nextTick(() => Promise.all([renderCharts(), loadMapCharts(widgets)]));
    if (generation !== loadGeneration) return;
    resetRefreshTimer();
    resetWidgetRefreshTimers(widgets);
    resetCarouselTimers(widgets);
    postBridge("bridge:ready", {
      designSize: { width: canvasWidth.value, height: canvasHeight.value },
      components: widgets.map(widget => ({ id: widget.id, type: widget.type, name: widget.name || widget.style?.title || typeLabels[widget.type] || widget.type })),
    });
  });
}

// Refreshing data does not recreate the canvas, controls, media or scroll tracks.
function refreshData(options = {}) {
  if (!schema.value || loading.value) return Promise.resolve();
  const wholePage = !options.widgets;
  if (wholePage && pageRefreshTask) return pageRefreshTask;
  const generation = loadGeneration;
  const work = async () => {
    if (wholePage) { refreshing.value = true; refreshNotice.value = ""; }
    try {
      if (options.checkConfiguration !== false) {
        const response = await requestRuntimeConfiguration();
        if (generation !== loadGeneration) return;
        const data = response.data || {};
        if (runtimeConfigurationKey(data) !== loadedConfigurationKey) return await applyRuntimeConfiguration(data);
      }
      if (generation !== loadGeneration) return;
      const widgets = (options.widgets || schema.value.widgets || []).filter(widget =>
        options.widgets || (widget.binding?.datasetCode && widget.binding?.sourceType !== 'STATIC' && overrideData[widget.id] === undefined &&
          (options.scope !== 'inherited' || (!Number(widget.binding?.refreshSeconds) && datasetType(widget) !== 'WEBSOCKET'))));
      await runDashboardRefreshBatch(widgets, widget => generation === loadGeneration ? loadWidget(widget, generation) : undefined);
      if (generation !== loadGeneration) return;
      if (needsConfigurationCheck) {
        needsConfigurationCheck = false;
        const response = await requestRuntimeConfiguration();
        if (generation !== loadGeneration) return;
        if (runtimeConfigurationKey(response.data || {}) !== loadedConfigurationKey) return await applyRuntimeConfiguration(response.data);
      }
      if (wholePage) lastLoaded.value = formatDashboardDateTime(new Date());
    } catch (cause) {
      if (generation !== loadGeneration) return;
      const failure = dashboardRefreshError(cause);
      if (failure.transient) {
        refreshNotice.value = '更新暂不可用，保留上次显示的数据';
      } else {
        // Revoked shares, expired logins and disabled pages must not retain a visible old frame.
        invalidateRuntime(failure);
      }
    } finally {
      if (generation === loadGeneration && wholePage) refreshing.value = false;
    }
  };
  const task = work();
  if (wholePage) {
    pageRefreshTask = task;
    task.finally(() => { if (pageRefreshTask === task) pageRefreshTask = undefined; });
  }
  return task;
}

function loadDictionaries(catalog) {
  // 分享页允许匿名访问；系统字典接口属于登录态管理接口，匿名调用会触发
  // 全局 401 登录提示。分享响应只提供页面声明的字段元数据，因此分享态
  // 保留原始字典值即可，不能为了展示标签而跨出分享令牌的授权边界；这也
  // 避免浏览器中残留的过期登录 Cookie 让匿名分享弹出登录框。
  if (isShareMode.value) return;
  const codes = new Set();
  catalog.forEach((dataset) => {
    parseResultArray(dataset?.fieldSchemaJson).forEach((field) => {
      const code = String(field?.dictCode || "").trim();
      if (/^[A-Za-z0-9_.-]{2,64}$/.test(code)) codes.add(code);
    });
  });
  codes.forEach((code) => {
    if (dictionaryCache[code] || dictionaryRequests.has(code)) return;
    const request = getDicts(code)
      .then((response) => {
        dictionaryCache[code] = (response.data || []).map((item) => ({
          label: item.dictLabel,
          value: String(item.dictValue),
          type: item.listClass,
          cssClass: item.cssClass,
        }));
        return nextTick(renderCharts);
      })
      .catch(() => {
        dictionaryCache[code] = [];
      })
      .finally(() => dictionaryRequests.delete(code));
    dictionaryRequests.set(code, request);
  });
}

function requestForWidget(widget) {
  const linked = linkedFilters[widget.id] || {};
  const params = {
    ...(widget.binding?.parameters || {}),
    ...(linked.params || {}),
  };
  const filters = [
    ...(widget.binding?.filters || []),
    ...(linked.filters || []),
  ];
  pageFilters.value.forEach((filter) => {
    const targets = Array.isArray(filter.targetWidgetIds)
      ? filter.targetWidgetIds
      : [];
    const value = pageFilterValues[filter.id];
    if (
      filter.parameter &&
      (!targets.length || targets.includes(widget.id)) &&
      value !== "" &&
      value !== null &&
      value !== undefined
    )
      params[filter.parameter] = value;
  });
  return { params, filters };
}

function changePageFilter() {
  if (!schema.value) return;
  const widgets = schema.value.widgets.filter(widget => widget.binding?.datasetCode &&
    widgetResultKeys.get(widget.id) !== JSON.stringify([widget.binding, requestForWidget(widget), overrideData[widget.id]]));
  refreshData({ widgets, checkConfiguration: false });
}

function mediaContext(widget) {
  return { pageId, revisionId: loadedRevisionId || revisionId.value || null, widgetId: widget.id,
    datasetCode: widget.binding?.datasetCode, shareToken: isShareMode.value ? shareToken.value : '', pageCode: routePageCode.value };
}

function loadWidget(widget, generation = loadGeneration) {
  if (generation !== loadGeneration) return Promise.resolve();
  const request = requestForWidget(widget);
  const key = JSON.stringify([widget.binding, request, overrideData[widget.id]]);
  const active = widgetRequests.get(widget.id);
  if (active && active.generation === generation && active.key === key) return active.promise;
  const pending = { key, generation };
  widgetRequests.set(widget.id, pending);
  pending.promise = requestWidgetData(widget, request, pending).finally(() => {
    if (widgetRequests.get(widget.id) === pending) widgetRequests.delete(widget.id);
  });
  return pending.promise;
}

async function applyWidgetResult(widget, value, pending) {
  if (pending.generation !== loadGeneration || widgetRequests.get(widget.id) !== pending) return;
  return commitWidgetResult(widget, value, pending.key, pending.generation);
}

async function commitWidgetResult(widget, value, key, generation) {
  if (generation !== loadGeneration) return;
  const result = normalizeResult(widget.binding?.datasetCode, value);
  if (result.revisionId && loadedRevisionId && Number(result.revisionId) !== loadedRevisionId) {
    needsConfigurationCheck = true;
    if (isShareMode.value) checkShareVersion();
    return;
  }
  if (result.quality === 'FORBIDDEN' || result.quality === 'AUTH_ERROR') {
    needsConfigurationCheck = true;
    if (isShareMode.value) checkShareVersion();
  }
  const previous = widgetStates[widget.id];
  const next = mergeDashboardRefreshResult(previous, result, widgetResultKeys.get(widget.id) === key);
  widgetStates[widget.id] = next;
  widgetResultKeys.set(widget.id, key);
  if (previous?.rows !== next.rows || previous?.quality !== next.quality) notifyCustomData(widget);
  await nextTick(() => {
    if (generation !== loadGeneration) return;
    renderCharts([widget.id]);
    if (isMapWidget(widget.type)) return loadMapCharts([widget]);
  });
}

async function requestWidgetData(widget, request, pending) {
  if (!dashboardComponentCapabilities(widget).data) {
    const mediaErrorRef = widget.style?.videoRef || "";
    const mediaError = widgetStates[widget.id]?.mediaError &&
      widgetStates[widget.id]?.mediaErrorRef === mediaErrorRef;
    return applyWidgetResult(widget, {
      rows: [],
      quality: mediaError ? "INVALID_DATA" : null,
      stale: false,
      ...(mediaError ? { mediaError: true, mediaErrorRef, message: "媒体资源加载失败" } : {}),
    }, pending);
  }
  const code = widget.binding?.datasetCode;
  if (overrideData[widget.id] !== undefined) return applyWidgetResult(widget, normalizeResult(code, overrideData[widget.id]), pending);
  const staticRows = Array.isArray(widget.binding?.staticRows) ? widget.binding.staticRows : null;
  if (widget.binding?.sourceType === 'STATIC' || (staticRows && !code))
    return applyWidgetResult(widget, { rows: staticRows || [], quality: staticRows?.length ? 'SUCCESS' : 'NO_DATA', stale: false, fetchedAt: new Date().toISOString() }, pending);
  if (!code) return applyWidgetResult(widget, { rows: [], quality: isStaticWidget(widget) ? null : 'NOT_CONNECTED' }, pending);
  if (datasetType(widget) === 'WEBSOCKET') {
    if (pending.generation !== loadGeneration) return;
    const socket = wsConnections[widget.id];
    if (!socket || socket.readyState > 1 || socketRequestKeys.get(widget.id) !== pending.key) {
      if (socket) closeWidgetSocket(widget.id);
      openWebSocket(widget, code);
    } else if (socket.readyState === 1) {
      window.clearTimeout(socketRefreshTimers.get(widget.id));
      socketRefreshTimers.set(widget.id, window.setTimeout(() => {
        if (pending.generation !== loadGeneration || wsConnections[widget.id] !== socket || socket.readyState !== 1) return;
        socketRequestKeys.set(widget.id, pending.key);
        try { socket.send(JSON.stringify({ type: 'refresh', params: request.params, filters: request.filters })); } catch { /* onclose handles reconnect */ }
      }, 300));
    }
    return;
  }
  try {
    const result = isShareMode.value
      ? routePageCode.value
        ? await fetchDashboardSharePageData(shareToken.value, routePageCode.value, widget.id, code, request.params, request.filters, runtimeRequestOptions)
        : await fetchDashboardShareData(shareToken.value, pageId, widget.id, code, request.params, request.filters, runtimeRequestOptions)
      : await fetchDashboardData(pageId, widget.id, code, request.params, isPreview.value, request.filters, revisionId.value || null, runtimeRequestOptions);
    return await applyWidgetResult(widget, result, pending);
  } catch (cause) {
    return applyWidgetResult(widget, dashboardRefreshError(cause), pending);
  }
}

function normalizeResult(datasetCode, value) {
  if (value && typeof value === "object" && Array.isArray(value.rows))
    return { datasetCode, ...value };
  return {
    datasetCode,
    rows: Array.isArray(value) ? value : [value],
    quality: "SUCCESS",
    stale: false,
    fetchedAt: new Date().toISOString(),
  };
}

function classifyError(error) {
  return String(error?.message || "")
    .toLowerCase()
    .includes("timeout")
    ? "TIMEOUT"
    : "SOURCE_ERROR";
}

function clearRefreshTimer() {
  if (!refreshTimer) return;
  window.clearTimeout(refreshTimer);
  window.clearInterval(refreshTimer);
  refreshTimer = undefined;
}

function nextDailyDelay(value) {
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(String(value || "08:00"));
  const target = new Date();
  target.setHours(Number(match?.[1] || 8), Number(match?.[2] || 0), 0, 0);
  if (target.getTime() <= Date.now()) target.setDate(target.getDate() + 1);
  return Math.max(1000, target.getTime() - Date.now());
}

function resetRefreshTimer() {
  clearRefreshTimer();
  const refresh = schema.value?.refresh || {};
  if (refresh.enabled === false || isPreview.value) return;
  const generation = loadGeneration;
  const delay = refresh.mode === 'daily' ? nextDailyDelay(refresh.at) : Math.max(5, Math.min(3600, Number(refresh.seconds) || 30)) * 1000;
  refreshTimer = window.setTimeout(async () => {
    await refreshData({ scope: 'inherited' });
    if (generation === loadGeneration && schema.value?.refresh?.enabled !== false && !isPreview.value) resetRefreshTimer();
  }, delay);
}
function resetWidgetRefreshTimers(widgets) {
  Object.values(widgetRefreshTimers).forEach(timer => window.clearInterval(timer));
  Object.keys(widgetRefreshTimers).forEach(key => delete widgetRefreshTimers[key]);
  if (isPreview.value) return;
  widgets.filter(widget => widget.binding?.datasetCode && widget.binding?.sourceType !== 'STATIC' && widget.binding?.refreshSeconds > 0 && datasetType(widget) !== 'WEBSOCKET').forEach(widget => {
    const seconds = Math.max(5, Math.min(3600, Number(widget.binding.refreshSeconds)));
    widgetRefreshTimers[widget.id] = window.setInterval(() => {
      refreshData({ widgets: [widget], checkConfiguration: false });
    }, seconds * 1000);
  });
}
function datasetType(widget) {
  return datasetCatalog.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  )?.dataType;
}

function openWebSocket(widget, datasetCode) {
  const request = requestForWidget(widget);
  const generation = loadGeneration;
  const requestKey = JSON.stringify([widget.binding, request, overrideData[widget.id]]);
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const basePath = import.meta.env.VITE_APP_BASE_API || "/dev-api";
  const token = encodeURIComponent(getToken() || "");
  const preview = isPreview.value ? "&preview=true" : "";
  const historyRevision =
    isPreview.value && revisionId.value
      ? `&revisionId=${encodeURIComponent(revisionId.value)}`
      : "";
  const shareQuery = isShareMode.value
    ? `&token=${encodeURIComponent(shareToken.value)}${
        routePageCode.value
          ? `&pageCode=${encodeURIComponent(routePageCode.value)}`
          : ""
      }`
    : `&token=${token}`;
  const socketPath = isShareMode.value
    ? "/dashboard/runtime/share/ws"
    : "/dashboard/runtime/ws";
  const socket = new WebSocket(
    `${protocol}//${window.location.host}${basePath}${socketPath}?pageId=${pageId}&widgetId=${encodeURIComponent(widget.id)}&datasetCode=${encodeURIComponent(datasetCode)}${shareQuery}${preview}${historyRevision}`,
  );
  wsConnections[widget.id] = socket;
  socketRequestKeys.set(widget.id, requestKey);
  const isCurrent = () => generation === loadGeneration && wsConnections[widget.id] === socket;
  socket.onopen = () => {
    if (!isCurrent()) return;
    wsReconnectAttempts[widget.id] = 0;
    // WebSocket 首条消息是受控订阅，不携带来源地址或凭证；服务端仍会按页面
    // 组件绑定、数据集声明和过滤器白名单重新校验。
    try {
      socket.send(
        JSON.stringify({
          type: "subscribe",
          params: request.params,
          filters: request.filters,
        }),
      );
    } catch {
      /* 连接关闭时由 close 处理重连 */
    }
  };
  socket.onmessage = (event) => {
    if (!isCurrent()) return;
    const key = socketRequestKeys.get(widget.id);
    if (key !== JSON.stringify([widget.binding, requestForWidget(widget), overrideData[widget.id]])) return;
    try {
      const result = JSON.parse(event.data);
      if (!Array.isArray(result.rows)) throw new Error('Invalid rows');
      commitWidgetResult(widget, result, key, generation);
      wsReconnectAttempts[widget.id] = 0;
    } catch {
      commitWidgetResult(widget, { rows: [], quality: 'INVALID_DATA', message: '实时消息格式错误', transient: false }, key, generation);
    }
  };
  socket.onerror = () => {
    if (!isCurrent()) return;
    commitWidgetResult(widget, { rows: [], quality: 'CONNECT_ERROR', message: '实时连接失败', transient: true }, socketRequestKeys.get(widget.id), generation);
  };
  socket.onclose = (event) => {
    if (!isCurrent()) return;
    const denied = [1003, 1007, 1008].includes(event?.code);
    commitWidgetResult(widget, { rows: [], quality: denied ? 'AUTH_ERROR' : 'CONNECT_ERROR', message: denied ? '实时订阅已被拒绝' : '实时连接已断开', transient: !denied }, socketRequestKeys.get(widget.id), generation);
    if (denied) return;
    const attempts = Math.min(
      5,
      Number(wsReconnectAttempts[widget.id] || 0) + 1,
    );
    wsReconnectAttempts[widget.id] = attempts;
    if (attempts >= 5 || isPreview.value) return;
    const delay = Math.min(30000, 1000 * 2 ** (attempts - 1));
    window.clearTimeout(wsReconnectTimers[widget.id]);
    wsReconnectTimers[widget.id] = window.setTimeout(() => {
      if (isCurrent()) {
        delete wsConnections[widget.id];
        openWebSocket(widget, datasetCode);
      }
    }, delay);
  };
}

function runtimeElement(attribute, id) {
  const stage = stageElement.value;
  if (!stage) return null;
  return (
    Array.from(stage.querySelectorAll(`[${attribute}]`)).find(
      (element) => element.getAttribute(attribute) === String(id),
    ) || null
  );
}
function chartRenderSignature(widget, rows) {
  const dataset = datasetCatalog.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  );
  return {
    config: JSON.stringify([
      widget.type, widget.style, widget.binding,
      systemPalette(), dataset?.fieldSchemaJson, dictionaryCache,
    ]),
    rows: JSON.stringify(rows),
  };
}

function updateRuntimeChart(chart, option, reset) {
  // replaceMerge 只按 id 保留系列；稳定 id 才能执行数值过渡而非重播入场动画。
  const identifyComponents = (source) => {
    if (!source) return;
    ["series", "visualMap"].forEach((key) => {
      const entries = Array.isArray(source[key]) ? source[key] : [source[key]];
      entries.forEach((entry, index) => {
        if (entry && entry.id == null) {
          entry.id = `runtime-${key}-${entry.type || ""}-${entry.name || ""}-${index}`;
        }
      });
    });
  };
  identifyComponents(option);
  identifyComponents(option.baseOption);
  (option.options || []).forEach(identifyComponents);
  if (reset) {
    chart.setOption(option, { notMerge: true });
    return;
  }
  // 数据刷新保留用户的图例选择、缩放范围和地图漫游位置。
  const previous = chart.getOption();
  const copyState = (key, fields) => {
    if (!option[key] || !previous[key]) return;
    const incoming = Array.isArray(option[key]) ? option[key] : [option[key]];
    const existing = Array.isArray(previous[key]) ? previous[key] : [previous[key]];
    incoming.forEach((entry, index) => {
      const saved = entry.id
        ? existing.find((item) => item.id === entry.id) || existing[index]
        : existing[index];
      fields.forEach((field) => {
        if (saved?.[field] !== undefined) entry[field] = saved[field];
      });
    });
  };
  copyState("legend", ["selected"]);
  copyState("dataZoom", ["start", "end", "startValue", "endValue"]);
  copyState("geo", ["zoom", "center"]);
  copyState("timeline", ["currentIndex", "autoPlay"]);
  // 移除已经不存在的系列/视觉映射，避免普通 merge 遗留旧数据。
  chart.setOption(option, {
    notMerge: false,
    replaceMerge: ["series", "visualMap"],
    lazyUpdate: true,
  });
}

function clearIncrementalChartCaches() {
  chartRenderSignatures.clear();
  mapRenderSignatures.clear();
  mapRefreshRequests.clear();
  mapRegistrations.clear();
  mapGeoJsonCache.clear();
  mapRequests.clear();
}

function renderCharts(widgetIds) {
  const selectedIds = widgetIds == null ? null : new Set(widgetIds.map(String));
  const chartWidgets = visibleWidgets.value.filter((widget) => [
    "line-chart", "bar-chart", "custom-chart", "pie-chart", "ring-chart",
    "gauge", "progress", "funnel", "radar", "scatter", "area-chart",
    "pictorial-chart", "treemap-chart", "calendar-chart", "bar3d-chart",
  ].includes(widget.type));
  const activeIds = new Set(chartWidgets.map((widget) => String(widget.id)));
  Object.keys(chartInstances).forEach((id) => {
    if (activeIds.has(id)) return;
    chartInstances[id]?.dispose?.();
    delete chartInstances[id];
    delete chartElements[id];
    chartRenderSignatures.delete(id);
  });
  chartWidgets.forEach((widget) => {
    const id = String(widget.id);
    if (selectedIds && !selectedIds.has(id)) return;
    const element = runtimeElement("data-chart-widget-id", widget.id);
    if (!element || (chartElements[id] && chartElements[id] !== element)) {
      chartInstances[id]?.dispose?.();
      delete chartInstances[id];
      delete chartElements[id];
      chartRenderSignatures.delete(id);
    }
    if (!element) return;
    chartElements[id] = element;
    let chart = chartInstances[id];
    if (chart?.isDisposed?.()) {
      chartRenderSignatures.delete(id);
      chart = null;
    }
    if (!chart) chartInstances[id] = chart = echarts.init(element);
    const rows = widgetRows(widget);
    const signature = chartRenderSignature(widget, rows);
    const previous = chartRenderSignatures.get(id);
    if (previous?.config === signature.config && previous.rows === signature.rows) return;
    updateRuntimeChart(chart, buildOption(widget, rows), previous?.config !== signature.config);
    chartRenderSignatures.set(id, signature);
    chart.off("click");
    chart.on("click", (params) => {
      const dataIndex = Number.isInteger(params?.dataIndex)
        ? params.dataIndex
        : 0;
      const data =
        params?.data?.row ||
        rows[dataIndex] ||
        (params?.data && typeof params.data === "object" ? params.data : null);
      handleWidgetClick(widget, data);
    });
  });
}

async function loadMapCharts(widgets = visibleWidgets.value) {
  const allMapWidgets = visibleWidgets.value.filter((widget) => isMapWidget(widget.type));
  const activeIds = new Set(allMapWidgets
    .filter((widget) => widget.style?.mapRef)
    .map((widget) => String(widget.id)));
  Object.keys(mapInstances).forEach((id) => {
    if (activeIds.has(id)) return;
    mapInstances[id]?.dispose?.();
    delete mapInstances[id];
    delete mapElements[id];
    mapRenderSignatures.delete(id);
    mapRefreshRequests.delete(id);
  });
  Object.keys(mapStates).forEach((id) => {
    if (allMapWidgets.some((widget) => String(widget.id) === id)) return;
    delete mapStates[id];
    mapRefreshRequests.delete(id);
  });
  const selectedIds = new Set(widgets.map((widget) => String(widget.id)));
  const mapWidgets = allMapWidgets.filter((widget) => selectedIds.has(String(widget.id)));
  await Promise.all(mapWidgets.map(async (widget) => {
    const id = String(widget.id);
    const mapRef = String(widget.style?.mapRef || "");
    if (!mapRef) {
      mapStates[id] = { ready: false, loading: false, message: "" };
      mapRefreshRequests.delete(id);
      return;
    }
    const request = {};
    mapRefreshRequests.set(id, request);
    const previous = mapRenderSignatures.get(id);
    const keepVisible = mapStates[id]?.ready === true && previous?.mapRef === mapRef;
    if (!keepVisible) {
      mapStates[id] = { ready: false, loading: true, message: "地图资源加载中…" };
    }
    try {
      const geoJson = await getMapGeoJson(mapRef);
      await nextTick();
      if (mapRefreshRequests.get(id) !== request) return;
      const element = runtimeElement("data-map-widget-id", widget.id);
      if (!geoJson || !element) throw new Error("地图 GeoJSON 无效");
      renderMapChart(widget, geoJson);
      const pointCount =
        mapPoints(widget).filter((point) => point.coordinate).length +
        mapFlows(widget).length;
      const regionCount = mapRegions(widget).filter(
        (region) => region.name && Number.isFinite(Number(region.value)),
      ).length;
      mapStates[id] = {
        ready: true,
        loading: false,
        message: pointCount || regionCount
          ? ""
          : "地图已加载，请映射区域名称/数值或有效经纬度字段",
      };
    } catch (error) {
      if (mapRefreshRequests.get(id) !== request) return;
      mapStates[id] = {
        ready: keepVisible,
        loading: false,
        message: error?.message || "地图资源加载失败",
      };
      if (!keepVisible) {
        mapInstances[id]?.dispose?.();
        delete mapInstances[id];
        delete mapElements[id];
        mapRenderSignatures.delete(id);
      }
    } finally {
      if (mapRefreshRequests.get(id) === request) mapRefreshRequests.delete(id);
    }
  }));
}

function mapResourceUrl(mapRef) {
  return dashboardResourceUrl(mapRef);
}

async function getMapGeoJson(mapRef) {
  if (mapGeoJsonCache.has(mapRef)) return mapGeoJsonCache.get(mapRef);
  if (mapRequests.has(mapRef)) return mapRequests.get(mapRef);
  const url = mapResourceUrl(mapRef);
  if (!url) throw new Error("地图资源路径不受支持");
  const request = fetch(url, {
    headers: { Accept: "application/geo+json, application/json" },
    credentials: "same-origin",
  })
    .then((response) => {
      if (!response.ok)
        throw new Error(`地图资源加载失败（${response.status}）`);
      return response.json();
    })
    .then((value) => {
      if (!value || typeof value !== "object" || !Array.isArray(value.features))
        throw new Error("地图 GeoJSON 结构不合法");
      if (mapRequests.get(mapRef) === request) mapGeoJsonCache.set(mapRef, value);
      return value;
    })
    .finally(() => {
      if (mapRequests.get(mapRef) === request) mapRequests.delete(mapRef);
    });
  mapRequests.set(mapRef, request);
  return request;
}

function renderMapChart(widget, geoJson) {
  const id = String(widget.id);
  const element = runtimeElement("data-map-widget-id", widget.id);
  if (!element) return;
  if (mapElements[id] && mapElements[id] !== element) {
    mapInstances[id]?.dispose?.();
    delete mapInstances[id];
    mapRenderSignatures.delete(id);
  }
  mapElements[id] = element;
  let chart = mapInstances[id];
  if (chart?.isDisposed?.()) {
    mapRenderSignatures.delete(id);
    chart = null;
  }
  if (!chart) mapInstances[id] = chart = echarts.init(element);
  const mapRef = String(widget.style?.mapRef || "");
  const mapName = `dashboard-map-${encodeURIComponent(mapRef)}`;
  if (mapRegistrations.get(mapRef) !== geoJson) {
    echarts.registerMap(mapName, geoJson);
    mapRegistrations.set(mapRef, geoJson);
  }
  const rows = widgetRows(widget);
  const signature = { ...chartRenderSignature(widget, rows), mapRef, geoJson };
  const previous = mapRenderSignatures.get(id);
  if (previous?.config === signature.config && previous.rows === signature.rows && previous.geoJson === geoJson) return;
  const data = dashboardMapData(widget, rows, (field, value) => displayFieldValue(widget, field, value));
  const { regions } = data;
  const palette = widget.style?.chartConfig?.colors?.length
    ? widget.style.chartConfig.colors
    : systemPalette();
  updateRuntimeChart(chart, buildDashboardMapOption(widget, mapName, data, palette),
    previous?.config !== signature.config || previous?.geoJson !== geoJson);
  mapRenderSignatures.set(id, signature);
  chart.off("click");
  chart.on("click", (params) => {
    const row =
      params?.data?.row ||
      regions.find((item) => item.name === params?.name)?.row;
    if (row) handleWidgetClick(widget, row);
  });
}

function safeMapNumber(value, min, max, fallback) {
  const number = Number(value);
  return Number.isFinite(number)
    ? Math.max(min, Math.min(max, number))
    : fallback;
}

function safeStyleNumber(value, min, max, fallback) {
  const number = Number(value);
  return Number.isFinite(number)
    ? Math.max(min, Math.min(max, number))
    : fallback;
}

function isMapWidget(type) {
  return [
    "map-chart",
    "map-flow",
    "map-bar",
    "map-heat",
    "map-ranking",
    "map-timeline",
  ].includes(type);
}

function mapFlows(widget) {
  return dashboardMapData(widget, widgetRows(widget), (field, value) => displayFieldValue(widget, field, value)).flows;
}

function buildOption(widget, rows) {
  const map = widget.binding?.fieldMap || {};
  const category = map.category || Object.keys(rows[0] || {})[0] || "name";
  const valueFields = chartValueFields(widget, rows);
  const value =
    valueFields[0] || map.value || Object.keys(rows[0] || {})[1] || "value";
  const paletteColors = systemPalette();
  const color =
    widget.style?.chartConfig?.colors?.length
      ? widget.style.chartConfig.colors[0]
      : widget.style?.useSystemPalette === true
        ? paletteColors[0]
        : widget.style?.color || paletteColors[0] || "#35d4b0";
  let custom = {};
  try {
    if (widget.style?.optionJson) {
      custom = JSON.parse(widget.style.optionJson);
      if (!custom || typeof custom !== "object" || Array.isArray(custom))
        custom = {};
    }
  } catch {
    custom = {};
  }
  if (widget.type === "custom-chart") {
    const renderType = customChartTypes.has(widget.style?.customChartType)
      ? widget.style.customChartType
      : "bar-chart";
    const generated = buildOption(
      {
        ...widget,
        type: renderType,
        style: { ...widget.style, optionJson: "" },
      },
      rows,
    );
    return mergeSafeOption(generated, custom, true);
  }
  const labels = rows.map((row) =>
    displayFieldValue(widget, category, row[category]),
  );
  const chartConfig = widget.style?.chartConfig || {};
  const seriesValues = valueFields.map((field) =>
    rows.map((row) => scaleChartValue(row[field], chartConfig)),
  );
  const values =
    seriesValues[0] ||
    rows.map((row) => scaleChartValue(row[value], chartConfig));
  const palette =
    Array.isArray(chartConfig.colors) && chartConfig.colors.length
      ? chartConfig.colors
      : paletteColors;
  const seriesNames = valueFields.map((field) =>
    chartFieldTitle(widget, field),
  );
  const seriesColors = valueFields.map((field, index) =>
    valueFields.length === 1 ? color : palette[index % palette.length],
  );
  const axisLabel = {
    color: chartConfig.xAxis?.labelColor || "#91a1b4",
    fontSize: Math.max(
      9,
      Math.min(28, Number(chartConfig.xAxis?.labelFontSize) || 11),
    ),
    ...(Number.isFinite(Number(chartConfig.xAxis?.labelRotate))
      ? { rotate: Number(chartConfig.xAxis.labelRotate) }
      : {}),
  };
  const yAxisLabel = {
    color: chartConfig.yAxis?.labelColor || "#91a1b4",
    fontSize: Math.max(
      9,
      Math.min(28, Number(chartConfig.yAxis?.labelFontSize) || 11),
    ),
  };
  const axisLine = {
    show: chartConfig.xAxis?.axisLineShow !== false,
    lineStyle: { color: chartConfig.xAxis?.axisLineColor || "#33445b" },
  };
  const xAxisType = safeAxisType(chartConfig.xAxis?.type, "category");
  const xAxis = {
    type: xAxisType,
    show: chartConfig.xAxis?.show !== false,
    name: chartConfig.xAxis?.name || undefined,
    data: xAxisType === "category" ? labels : undefined,
    axisLabel,
    axisLine,
    splitLine: {
      show: chartConfig.xAxis?.splitLineShow === true,
      lineStyle: { color: chartConfig.xAxis?.splitLineColor || "#253246" },
    },
  };
  const gridConfig = chartConfig.grid || {};
  const grid = {
    left: Number(gridConfig.left ?? 42),
    right: Number(gridConfig.right ?? 18),
    top: Number(gridConfig.top ?? 24),
    bottom: Number(gridConfig.bottom ?? 30),
    containLabel: true,
  };
  const yAxisType = safeAxisType(chartConfig.yAxis?.type, "value");
  const yAxis = {
    type: yAxisType,
    show: chartConfig.yAxis?.show !== false,
    name: chartConfig.yAxis?.name || undefined,
    min: chartConfig.yAxis?.min ?? undefined,
    max: chartConfig.yAxis?.max ?? undefined,
    axisLabel: yAxisLabel,
    axisLine: {
      show: chartConfig.yAxis?.axisLineShow !== false,
      lineStyle: { color: chartConfig.yAxis?.axisLineColor || "#33445b" },
    },
    splitLine: {
      show: chartConfig.yAxis?.splitLineShow !== false,
      lineStyle: { color: chartConfig.yAxis?.splitLineColor || "#253246" },
    },
  };
  const legendMargin = chartConfig.legend?.margin || {};
  const legend = {
    show: chartConfig.legend?.show === true,
    orient: chartConfig.legend?.orient || "horizontal",
    left:
      chartConfig.legend?.position === "right"
        ? "right"
        : chartConfig.legend?.position === "left"
          ? "left"
          : "center",
    top:
      chartConfig.legend?.position === "top"
        ? "top"
        : chartConfig.legend?.position === "bottom"
          ? "bottom"
          : "middle",
    padding: [
      safeMargin(legendMargin.top),
      safeMargin(legendMargin.right),
      safeMargin(legendMargin.bottom),
      safeMargin(legendMargin.left),
    ],
    itemWidth: Math.max(
      6,
      Math.min(40, Number(chartConfig.legend?.itemWidth) || 14),
    ),
    itemHeight: Math.max(
      4,
      Math.min(24, Number(chartConfig.legend?.itemHeight) || 8),
    ),
    textStyle: {
      color: chartConfig.legend?.textColor || "#dce8f5",
      fontSize: Math.max(
        9,
        Math.min(28, Number(chartConfig.legend?.fontSize) || 12),
      ),
    },
  };
  if (widget.type === "ring-chart") {
    const legendValues = Object.fromEntries(
      rows.map((row, index) => [
        displayFieldValue(widget, category, row[category]),
        { value: values[index] || 0, index },
      ]),
    );
    const suffix = String(widget.style?.ringLegendSuffix || "");
    const columns = Math.round(
      safeStyleNumber(widget.style?.ringLegendColumns, 1, 4, 1),
    );
    legend.width = safeStyleNumber(
      widget.style?.ringLegendWidth,
      80,
      600,
      180,
    );
    legend.left = `${safeStyleNumber(widget.style?.ringLegendLeft, 0, 100, 50)}%`;
    legend.itemGap = safeStyleNumber(
      widget.style?.ringLegendItemGap,
      0,
      40,
      10,
    );
    if (legend.orient === "vertical" && columns > 1)
      legend.height = Math.max(legend.itemHeight, legend.textStyle.fontSize + 2) * Math.ceil(rows.length / columns) + legend.itemGap * Math.max(0, Math.ceil(rows.length / columns) - 1);
    if (widget.style?.ringLegendShowValue === true) {
      const labelWidth = Math.max(
        36,
        Math.floor(legend.width / columns - 62),
      );
      legend.textStyle.rich = {
        label: {
          width: labelWidth,
          color: legend.textStyle.color,
          fontSize: legend.textStyle.fontSize,
        },
        unit: { color: "#9aabb9", fontSize: Math.max(9, legend.textStyle.fontSize - 1) },
      };
      rows.forEach((row, index) => {
        legend.textStyle.rich[`value${index}`] = {
          width: 28,
          align: "right",
          color: palette[index % palette.length],
          fontSize: legend.textStyle.fontSize + 1,
          fontWeight: 700,
        };
      });
      legend.formatter = (name) => {
        const entry = legendValues[name] || { value: 0, index: 0 };
        return `{label|${name}}{value${entry.index}|${entry.value}}{unit|${suffix}}`;
      };
    }
  }
  const tooltip = {
    show: chartConfig.tooltip?.show !== false,
    trigger: "axis",
    textStyle: {
      color: chartConfig.tooltip?.textColor || "#dce8f5",
      fontSize: Math.max(
        10,
        Math.min(32, Number(chartConfig.tooltip?.fontSize) || 12),
      ),
    },
    backgroundColor: chartConfig.tooltip?.backgroundColor || undefined,
    borderColor: chartConfig.tooltip?.borderColor || undefined,
  };
  const label = {
    show: chartConfig.label?.show === true,
    position: chartConfig.label?.position || "top",
    color: chartConfig.label?.color || "#dce8f5",
    fontSize: Math.max(
      9,
      Math.min(32, Number(chartConfig.label?.fontSize) || 11),
    ),
    fontWeight: Number(chartConfig.label?.fontWeight) || 400,
    formatter: safeLabelFormatter(chartConfig.label?.format),
  };
  let option;
  if (widget.type === "pie-chart" || widget.type === "ring-chart")
    option = {
      tooltip: { ...tooltip, trigger: "item" },
      legend,
      color: palette,
      series: [
        {
          type: "pie",
          center: [
            `${Number(chartConfig.center?.x ?? 50)}%`,
            `${Number(chartConfig.center?.y ?? 50)}%`,
          ],
          radius:
            widget.type === "ring-chart"
              ? [
                  `${safeStyleNumber(widget.style?.ringInnerRadius, 0, 90, 46)}%`,
                  `${safeStyleNumber(widget.style?.ringOuterRadius, 1, 100, 72)}%`,
                ]
              : "65%",
          label,
          data: rows.map((row, index) => ({
            name: displayFieldValue(widget, category, row[category]),
            value:
              widget.type === "ring-chart" &&
              widget.style?.ringEqualSegments === true
                ? 1
                : values[index] || 0,
          })),
        },
      ],
    };
  else if (widget.type === "gauge")
    option = {
      tooltip: { ...tooltip, trigger: "item" },
      series: [
        {
          type: "gauge",
          progress: { show: true, itemStyle: { color } },
          axisLine: { lineStyle: { color: [[1, "#273548"]] } },
          detail: {
            valueAnimation: true,
            formatter: `{value}${valueUnitSuffix(widget, chartConfig)}`,
            color: "#dce8f5",
          },
          data: [{ value: values[0] || 0 }],
        },
      ],
    };
  else if (widget.type === "progress")
    option = {
      xAxis: { show: false, max: 100 },
      yAxis: { show: false, type: "category", data: [""] },
      grid: { left: 12, right: 12, top: 12, bottom: 12 },
      series: [
        {
          type: "bar",
          data: [values[0] || 0],
          barWidth: 16,
          showBackground: true,
          backgroundStyle: { color: "#253246", borderRadius: 8 },
          itemStyle: { color, borderRadius: 8 },
          label: {
            show: chartConfig.label?.show !== false,
            position: "right",
            formatter: `{c}${valueUnitSuffix(widget, chartConfig, "%")}`,
            color: "#dce8f5",
          },
        },
      ],
    };
  else if (widget.type === "funnel")
    option = {
      tooltip: { ...tooltip, trigger: "item" },
      legend,
      color: palette,
      series: [
        {
          type: "funnel",
          left: "10%",
          width: "80%",
          top: 15,
          bottom: 15,
          min: 0,
          max: Math.max(...values, 1),
          sort: "descending",
          gap: 2,
          label,
          data: rows.map((row, index) => ({
            name: displayFieldValue(widget, category, row[category]),
            value: values[index] || 0,
          })),
        },
      ],
    };
  else if (widget.type === "treemap-chart")
    option = {
      tooltip: { ...tooltip, trigger: "item" },
      color: palette,
      series: [
        {
          type: "treemap",
          roam: false,
          breadcrumb: { show: false },
          nodeClick: false,
          label: { ...label, show: true, formatter: "{b}\n{c}" },
          upperLabel: { show: false },
          itemStyle: { borderColor: "#0b1220", borderWidth: 2, gapWidth: 2 },
          data: rows.map((row, index) => ({
            name: displayFieldValue(widget, category, row[category]),
            value: values[index] || 0,
            row,
            itemStyle: { color: palette[index % palette.length] },
          })),
        },
      ],
    };
  else if (widget.type === "calendar-chart") {
    const calendarRows = rows
      .map((row, index) => ({
        row,
        date: normalizeCalendarDate(row[category]),
        value: values[index] ?? 0,
      }))
      .filter((item) => item.date);
    const year =
      calendarRows[0]?.date?.slice(0, 4) || String(new Date().getFullYear());
    option = {
      tooltip: { ...tooltip, trigger: "item" },
      visualMap: {
        show: false,
        min: Math.min(
          ...calendarRows.map((item) => Number(item.value) || 0),
          0,
        ),
        max: Math.max(
          ...calendarRows.map((item) => Number(item.value) || 0),
          1,
        ),
        inRange: { color: [palette[0], palette[1] || palette[0]] },
      },
      calendar: {
        range: year,
        left: 34,
        right: 12,
        top: 28,
        bottom: 14,
        cellSize: ["auto", 16],
        itemStyle: { color: "#132235", borderColor: "#25384d", borderWidth: 1 },
        dayLabel: { color: "#91a1b4", firstDay: 1 },
        monthLabel: { color: "#dce8f5" },
        yearLabel: { show: false },
        splitLine: { show: false },
      },
      series: [
        {
          type: "heatmap",
          coordinateSystem: "calendar",
          data: calendarRows.map((item) => ({
            value: [item.date, item.value],
            row: item.row,
          })),
          label: { show: false },
        },
      ],
    };
  } else if (widget.type === "radar")
    option = {
      tooltip,
      color: palette,
      radar: {
        center: [
          `${Number(chartConfig.center?.x ?? 50)}%`,
          `${Number(chartConfig.center?.y ?? 50)}%`,
        ],
        indicator: rows.map((row) => ({
          name: displayFieldValue(widget, category, row[category]),
          max: Math.max(...seriesValues.flat(), 1),
        })),
        axisName: { color: "#a9b8c8" },
      },
      series: seriesValues.map((series, index) => ({
        name: seriesNames[index],
        type: "radar",
        data: [
          {
            value: series,
            lineStyle: { color: seriesColors[index] },
            areaStyle: { color: seriesColors[index], opacity: 0.2 },
            itemStyle: { color: seriesColors[index] },
            label,
          },
        ],
      })),
    };
  else if (widget.type === "scatter")
    option = {
      tooltip,
      xAxis,
      yAxis,
      grid,
      series: [
        {
          type: "scatter",
          data: rows.map((row, index) => [
            Number.isFinite(Number(row[category])) ? Number(row[category]) : index,
            scaleChartValue(row[value], chartConfig),
          ]),
          itemStyle: { color },
          label,
        },
      ],
    };
  else if (widget.type === "pictorial-chart")
    option = {
      tooltip,
      color: palette,
      grid,
      xAxis,
      yAxis,
      series: [
        {
          type: "pictorialBar",
          symbol: "roundRect",
          symbolRepeat: true,
          symbolMargin: 2,
          symbolSize: ["70%", "14%"],
          data: values,
          itemStyle: { color },
          label,
        },
      ],
    };
  else if (widget.type === "bar3d-chart") {
    const depth = Math.max(
      3,
      Math.min(14, Number(widget.style?.bar3dDepth) || 8),
    );
    const gradient = new echarts.graphic.LinearGradient(0, 0, 0, 1, [
      { offset: 0, color: palette[0] || color },
      { offset: 1, color: palette[1] || color },
    ]);
    option = {
      tooltip: { ...tooltip, trigger: "axis" },
      grid,
      xAxis,
      yAxis,
      series: [
        {
          type: "bar",
          data: values,
          barWidth: `${Math.max(24, 68 - depth)}%`,
          itemStyle: { color: gradient, borderRadius: [3, 3, 0, 0] },
          label,
        },
        {
          type: "bar",
          data: values.map((value) => value * 0.96),
          barGap: "-100%",
          barWidth: `${Math.max(24, 68 - depth)}%`,
          itemStyle: { color: "rgba(0,0,0,.16)", opacity: 0.55 },
          silent: true,
        },
        {
          type: "line",
          data: values.map((value) => value * 0.9),
          symbol: "none",
          lineStyle: {
            color: palette[2] || color,
            width: depth / 2,
            opacity: 0.75,
          },
          silent: true,
        },
      ],
    };
  } else
    option = {
      tooltip,
      legend,
      color: palette,
      grid,
      xAxis,
      yAxis,
      series: seriesValues.map((series, index) => ({
        name: seriesNames[index],
        type: widget.type === "bar-chart" ? "bar" : "line",
        data: series,
        smooth: chartConfig.smooth !== false,
        stack: chartConfig.stack ? "total" : undefined,
        itemStyle: { color: seriesColors[index] },
        label,
        areaStyle:
          widget.type === "area-chart"
            ? {
                color: seriesColors[index],
                opacity: Number(chartConfig.areaOpacity ?? 0.2),
              }
            : undefined,
        markLine: index === 0 ? safeMarkLine(chartConfig.markLine) : undefined,
      })),
    };
  applyDashboardChartProperties(option, widget, {
    labels,
    values,
    categoryValues: rows.map((row) => row[category]),
    seriesNames: seriesNames,
    tooltip,
    label,
    legend,
  });
  return mergeSafeOption(option, custom);
}

function chartValueFields(widget, rows) {
  const map = widget.binding?.fieldMap || {};
  const configured = Array.isArray(map.valueFields)
    ? map.valueFields.filter(Boolean)
    : [];
  const available = new Set(rows.flatMap((row) => Object.keys(row || {})));
  const valid = configured.filter(
    (field) => available.size === 0 || available.has(field),
  );
  const fallback = map.value || Object.keys(rows[0] || {})[1] || "value";
  return [...new Set(valid.length ? valid : [fallback])];
}

function chartFieldTitle(widget, field) {
  const dataset = datasetCatalog.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  );
  const fields = parseResultArray(dataset?.fieldSchemaJson);
  return fields.find((item) => item.name === field)?.title || field;
}

function normalizeCalendarDate(value) {
  const formatted = formatDashboardDate(value, "");
  return /^\d{4}-\d{2}-\d{2}$/.test(formatted) ? formatted : "";
}


function safeLabelFormatter(value) {
  const text = String(value || "").trim();
  return /^[^{}]*(?:\{[abcd]\}[^{}]*)*$/.test(text) ? text || "{c}" : "{c}";
}

function safeAxisType(value, fallback) {
  return ["category", "value", "time", "log"].includes(String(value))
    ? String(value)
    : fallback;
}

function safeMargin(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(0, Math.min(240, number)) : 0;
}

function normalizePrecision(value) {
  const number = Number(value);
  return Number.isFinite(number)
    ? Math.max(0, Math.min(6, Math.trunc(number)))
    : 2;
}

const chartScaleMeta = {
  none: { factor: 1, label: "" },
  thousand: { factor: 1000, label: "千" },
  "ten-thousand": { factor: 10000, label: "万" },
  million: { factor: 1000000, label: "百万" },
};

function chartScale(config) {
  return chartScaleMeta[config?.valueScale] || chartScaleMeta.none;
}
function scaleChartValue(value, config) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  const scaled = number / chartScale(config).factor;
  const precision = normalizePrecision(config?.valuePrecision);
  return Number(scaled.toFixed(precision));
}
function valueUnitSuffix(widget, config, fallback = "") {
  return `${widget.style?.unit || fallback}${chartScale(config).label}`;
}

function safeMarkLine(value) {
  if (!value || value.show !== true || !Number.isFinite(Number(value.value)))
    return undefined;
  const lineType = ["solid", "dashed", "dotted"].includes(value.lineType)
    ? value.lineType
    : "dashed";
  const color = /^#[0-9a-fA-F]{3,8}$/.test(String(value.color || ""))
    ? value.color
    : "#e7ab47";
  const label = String(value.label || "")
    .trim()
    .slice(0, 64);
  return {
    symbol: "none",
    silent: true,
    lineStyle: { color, type: lineType },
    label: {
      show: true,
      formatter: label || String(value.value),
      color: "#dce8f5",
      fontSize: 10,
    },
    data: [{ yAxis: Number(value.value) }],
  };
}

function metricValue(widget) {
  const state = widgetStates[widget.id] || {};
  const row = state.rows?.[0] || {};
  const key = widget.binding?.fieldMap?.value || Object.keys(row).find((name) => row[name] !== null && row[name] !== "" && Number.isFinite(Number(row[name]))) || Object.keys(row)[0];
  if (!key) return "-";
  const value = key === "total" && row[key] === undefined && state.total !== undefined ? state.total : row[key];
  return formatDashboardMetricDisplay(formatValue(value, datasetField(widget, key)), widget.style);
}
function numberFlipCharacters(widget) {
  const raw = String(metricValue(widget) ?? "");
  const minDigits = Math.max(
    1,
    Math.min(12, Number(widget.style?.flipMinDigits) || 1),
  );
  const sign = raw.startsWith("-") ? "-" : "";
  const unsigned = sign ? raw.slice(1) : raw;
  const match = unsigned.match(/^(\d+)(.*)$/);
  if (!match) return [...raw];
  return [...`${sign}${match[1].padStart(minDigits, "0")}${match[2]}`];
}
function isWidgetTitleVisible(widget) {
  return dashboardWidgetTitleVisible(widget, statisticsHeadingTitle(widget));
}
function statisticsHeadingTitle(widget) {
  return widget.type === "statistics"
    ? dashboardTitleText(statisticsValue(widget, "label", dashboardTitleText(widget.style?.title)))
    : dashboardTitleText(widget.style?.title);
}
function statisticsValue(widget, role, fallback = "") {
  const { key, value } = dashboardStatisticsField(widget, widgetStates[widget.id]?.rows?.[0] || {}, role);
  if (dashboardTitleText(value) === "") return fallback;
  const text = formatValue(value, key ? datasetField(widget, key) : undefined);
  if (role === "label") return dashboardTitleText(text);
  return role === "value" ? formatDashboardMetricDisplay(text, widget.style) : text;
}
function accessListRows(widget) {
  const rows = widgetRows(widget);
  const limit = Math.max(
    1,
    Math.min(50, Number(widget.binding?.rowLimit) || 3),
  );
  return rows.slice(0, limit);
}
function accessListValue(widget, row, role) {
  const key = widget.binding?.fieldMap?.[role];
  if (!key) return "";
  const value = row?.[key];
  if (value === null || value === undefined) return "";
  return String(formatDateFieldValue(value, datasetField(widget, key)));
}
function accessStatusSuccess(widget, row) {
  const value = accessListValue(widget, row, "status").trim();
  const successText = String(
    widget.style?.accessStatusSuccessText || "进场",
  ).trim();
  return value === successText;
}
function accessStatusText(widget, row) {
  const value = accessListValue(widget, row, "status").trim();
  const successText = String(
    widget.style?.accessStatusSuccessText || "进场",
  ).trim();
  const neutralText = String(
    widget.style?.accessStatusNeutralText || "出场",
  ).trim();
  if (!value) return neutralText || "—";
  if (value === successText) return successText || value;
  if (value === neutralText) return neutralText || value;
  return value;
}
function statisticsCompareText(widget) {
  const text = statisticsValue(widget, "compareValue", "—");
  if (text === "—") return text;
  return `${statisticsCompareClass(widget) === "compare-down" ? "↓" : "↑"} ${text}`;
}
function statisticsCompareClass(widget) {
  const text = String(statisticsValue(widget, "compareState", "")).trim().toLowerCase();
  return text.includes("down") || text.includes("下降") || text === "decrease"
    ? "compare-down"
    : "compare-up";
}
function carouselRows(widget) {
  const rows = widgetStates[widget.id]?.rows || [];
  const limit = Math.max(1, Math.min(6, Number(widget.binding?.rowLimit) || 3));
  return dashboardCarouselRows(rows, limit, carouselOffsets[widget.id]);
}
function advancedDisplayRows(widget) {
  const rows = displayRows(widget);
  if (
    !rows.length ||
    rows.length <= 6 ||
    widget.style?.advancedScroll === false
  )
    return rows;
  const offset = Number(advancedOffsets[widget.id] || 0) % rows.length;
  const visible = Math.min(6, rows.length);
  return [
    ...rows.slice(offset, offset + visible),
    ...rows.slice(0, Math.max(0, offset + visible - rows.length)),
  ];
}
function ringTextItems(widget) {
  const rows = widgetRows(widget);
  const map = widget.binding?.fieldMap || {};
  const key = map.label || map.category || Object.keys(rows[0] || {})[0];
  const values = rows
    .map((row) => displayFieldValue(widget, key, row[key]))
    .filter(Boolean);
  return values.length
    ? values.slice(0, 24)
    : ["项目进度", "质量", "安全", "人员"];
}
function milestoneRows(widget) {
  const rows = widgetRows(widget);
  const map = widget.binding?.fieldMap || {};
  return rows.map((row, index) => {
    const status = String(row[map.status || "status"] || "")
      .trim()
      .toLowerCase();
    const done = ["done", "completed", "已完成", "完成"].includes(status);
    const active = ["active", "current", "进行中", "当前"].includes(status);
    const startValue = row[map.startDate || "startDate"];
    const endValue = row[map.endDate || "endDate"];
    return {
      id: row.id || index,
      label: formatValue(
        row[map.label || "label"] ?? row.name ?? `阶段 ${index + 1}`,
      ),
      startDate: milestoneDate(widget, startValue),
      endDate: milestoneDate(widget, endValue),
      done,
      active,
      row,
    };
  });
}
function milestoneDate(widget, value) {
  const result = formatDashboardDate(value, value ? String(value) : "");
  if (String(result) === "-") return "-";
  if (widget.style?.timelineDateFormat === "MM-DD") return String(result).slice(5);
  return widget.style?.timelineDateFormat === "YYYY/MM/DD"
    ? String(result).replaceAll("-", "/")
    : result;
}
function widgetRows(widget) {
  const rows = [...(widgetStates[widget.id]?.rows || [])];
  const limit = Math.max(
    1,
    Math.min(1000, Number(widget.binding?.rowLimit) || 50),
  );
  if (widget.type === "rank-table") {
    const valueKey =
      widget.binding?.fieldMap?.value || Object.keys(rows[0] || {})[1];
    rows.sort((a, b) => Number(b[valueKey]) - Number(a[valueKey]));
  }
  if (widget.type === "carousel-table" && rows.length > limit) {
    const offset = Number(carouselOffsets[widget.id] || 0) % rows.length;
    return [
      ...rows.slice(offset, offset + limit),
      ...rows.slice(0, Math.max(0, offset + limit - rows.length)),
    ];
  }
  return rows.slice(0, limit);
}
function columns(widget) {
  const row = widgetRows(widget)[0] || {};
  const dataset = datasetCatalog.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  );
  const fields = parseResultArray(dataset?.fieldSchemaJson).filter(
    (field) => field.show !== false && field.name,
  );
  const selected =
    Array.isArray(widget.binding?.displayFields) &&
    widget.binding.displayFields.length
      ? widget.binding.displayFields
      : fields.map((field) => field.name);
  if (fields.length)
    return selected
      .map((name) => fields.find((field) => field.name === name))
      .filter(
        (field) =>
          field && Object.prototype.hasOwnProperty.call(row, field.name),
      );
  return selected.length
    ? selected
        .filter((name) => Object.prototype.hasOwnProperty.call(row, name))
        .map((name) => ({ name, title: name }))
    : Object.keys(row).map((name) => ({ name, title: name }));
}
function displayRows(widget) {
  const rows = widgetRows(widget);
  const state = sortState[widget.id];
  if (!state?.field) return rows;
  return [...rows].sort((left, right) =>
    compareDisplayValues(
      left[state.field],
      right[state.field],
      state.direction,
    ),
  );
}
function sortTable(widget, column) {
  const current = sortState[widget.id];
  sortState[widget.id] = {
    field: column.name,
    direction:
      current?.field === column.name && current.direction === "asc"
        ? "desc"
        : "asc",
  };
}
function compareDisplayValues(left, right, direction) {
  const a = left === null || left === undefined ? "" : left;
  const b = right === null || right === undefined ? "" : right;
  const numeric = Number(a) - Number(b);
  const result = Number.isNaN(numeric)
    ? String(a).localeCompare(String(b), "zh-Hans-CN")
    : numeric;
  return direction === "desc" ? -result : result;
}
function dictionaryLabel(field, value) {
  if (!field?.dictCode || value === null || value === undefined) return null;
  const option = (dictionaryCache[field.dictCode] || []).find(
    (item) => String(item.value) === String(value),
  );
  return option?.label ?? null;
}
function datasetField(widget, fieldName) {
  if (!fieldName) return {};
  const dataset = datasetCatalog.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  );
  return (
    parseResultArray(dataset?.fieldSchemaJson).find(
      (field) => field?.name === fieldName,
    ) || {}
  );
}
function displayFieldValue(widget, fieldName, value) {
  const field = datasetField(widget, fieldName);
  const displayValue =
    dictionaryLabel(field, value) ??
    (value === null || value === undefined ? "" : String(value));
  return formatDateFieldValue(displayValue, field);
}
function formatDateFieldValue(value, field = {}) {
  return formatDashboardFieldValue(value, field, value);
}
function formatValue(value, field = {}) {
  if (value === null || value === undefined || value === "") return "-";
  const dictionaryValue = dictionaryLabel(field, value);
  const displayValue = formatDateFieldValue(dictionaryValue ?? value, field);
  if (field.mask) {
    const text = String(displayValue);
    return text.length <= 2
      ? "*".repeat(text.length)
      : `${text.slice(0, 1)}${"*".repeat(Math.min(4, text.length - 2))}${text.slice(-1)}`;
  }
  return typeof displayValue === "object"
    ? JSON.stringify(displayValue)
    : String(displayValue);
}
function systemPalette() {
  const canvasColors = schema.value?.canvas?.paletteColors;
  return Array.isArray(canvasColors) && canvasColors.length
    ? canvasColors
    : palettePresets.teal;
}

function wordCloudItems(widget) {
  const rows = widgetRows(widget);
  const map = widget.binding?.fieldMap || {};
  const category = map.category || Object.keys(rows[0] || {})[0] || "name";
  const value = map.value || Object.keys(rows[0] || {})[1] || "value";
  const scores = rows.map((row) => Number(row[value]) || 0);
  const max = Math.max(...scores, 1);
  const min = Math.min(...scores, 0);
  const colors = widget.style?.chartConfig?.colors?.length
    ? widget.style.chartConfig.colors
    : systemPalette();
  return rows
    .slice(0, 100)
    .map((row, index) => ({
      row,
      label: displayFieldValue(widget, category, row[category]),
      size: Math.round(
        14 + (((Number(row[value]) || 0) - min) / Math.max(max - min, 1)) * 28,
      ),
      color: colors[index % colors.length],
    }));
}
function currentTimeText(widget) {
  const value = new Date(clockNow.value);
  const format = widget.style?.timeFormat || "HH:mm:ss";
  const pad = (number) => String(number).padStart(2, "0");
  const date = `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`;
  const time = `${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`;
  if (format === "HH:mm") return time.slice(0, 5);
  if (format === "YYYY-MM-DD") return date;
  if (format === "YYYY-MM-DD HH:mm") return `${date} ${time.slice(0, 5)}`;
  if (format === "YYYY-MM-DD HH:mm:ss") return `${date} ${time}`;
  return time;
}
function weatherValue(widget, role, fallback = "") {
  const rows = widgetRows(widget);
  const row = rows[0] || {};
  const key = widget.binding?.fieldMap?.[role];
  const value = key
    ? row[key]
    : role === "temperature"
      ? (row.temperature ?? row.temp)
      : role === "condition"
        ? (row.condition ?? row.weather)
        : (row.city ?? row.location);
  return value === null || value === undefined || value === ""
    ? fallback
    : formatValue(value);
}
function colorBlockValue(widget) {
  const rows = widgetRows(widget);
  const row = rows[0] || {};
  const key = widget.binding?.fieldMap?.value;
  const value = key ? row[key] : row.color;
  return dashboardColorBlockValue(value, widget.style);
}
function colorBlockLabel(widget) {
  const rows = widgetRows(widget);
  const row = rows[0] || {};
  const key = widget.binding?.fieldMap?.label;
  return key && row[key] !== undefined
    ? formatValue(row[key])
    : widget.style?.blockLabel || "状态";
}
function mapPoints(widget) {
  const rows = widgetRows(widget);
  const map = widget.binding?.fieldMap || {};
  const labelKey =
    map.label || map.name || map.category || Object.keys(rows[0] || {})[0];
  const valueKey = map.value || Object.keys(rows[0] || {})[1];
  const longitudeKey =
    map.longitude || map.lng || map.lon || map.x || "longitude";
  const latitudeKey = map.latitude || map.lat || map.y || "latitude";
  const colors = widget.style?.chartConfig?.colors?.length
    ? widget.style.chartConfig.colors
    : systemPalette();
  return rows.slice(0, 200).map((row, index) => {
    const longitude = Number(row[longitudeKey]);
    const latitude = Number(row[latitudeKey]);
    const validCoordinate =
      row[longitudeKey] !== null && row[longitudeKey] !== undefined && row[longitudeKey] !== "" &&
      row[latitudeKey] !== null && row[latitudeKey] !== undefined && row[latitudeKey] !== "" &&
      Number.isFinite(longitude) &&
      Number.isFinite(latitude) &&
      longitude >= -180 &&
      longitude <= 180 &&
      latitude >= -90 &&
      latitude <= 90;
    const fallbackX = validCoordinate
      ? Math.max(2, Math.min(98, ((longitude + 180) / 360) * 100))
      : Number.isFinite(longitude) && longitude >= 0 && longitude <= 100
        ? Math.max(2, Math.min(98, longitude))
        : 12 + ((index * 29) % 76);
    const fallbackY = validCoordinate
      ? Math.max(4, Math.min(94, ((90 - latitude) / 180) * 100))
      : Number.isFinite(latitude) && latitude >= 0 && latitude <= 100
        ? Math.max(4, Math.min(94, latitude))
        : 12 + ((index * 37) % 72);
    return {
      row,
      label: displayFieldValue(widget, labelKey, row[labelKey]),
      value: row[valueKey],
      coordinate: validCoordinate ? [longitude, latitude] : null,
      x: fallbackX,
      y: fallbackY,
      color: colors[index % colors.length],
    };
  });
}
function mapRegions(widget) {
  return dashboardMapData(widget, widgetRows(widget), (field, value) => displayFieldValue(widget, field, value)).regions;
}

function fallbackMapPoints(widget) {
  return mapPoints(widget);
}
function markMediaError(widget) {
  widgetStates[widget.id] = {
    ...(widgetStates[widget.id] || {}),
    quality: "INVALID_DATA",
    mediaError: true,
    mediaErrorRef: widget.style?.videoRef || "",
    message: "媒体资源加载失败",
  };
}
function formatTime(value) {
  return formatDashboardDateTime(value, "");
}
function hasTitleImage(widget) {
  return (
    dashboardWidgetTitleVisible(widget) &&
    widget?.style?.titleImageEnabled === true &&
    Boolean(dashboardTitleText(widget?.style?.titleImageRef))
  );
}
function hasWidgetQuality(widget) {
  return dashboardComponentCapabilities(widget).data &&
    widget.style?.qualityVisible !== false &&
    Boolean(widgetStates[widget.id]?.quality);
}
function titleImageSize(fit) {
  return fit === "contain"
    ? "contain"
    : fit === "cover"
      ? "cover"
      : "100% 100%";
}
function titleImagePosition(fit, align) {
  return fit === "contain"
    ? `${["left", "center", "right"].includes(align) ? align : "left"} center`
    : "center center";
}
function widgetStyle(widget) {
  const l = widget.layout || {};
  const s = widget.style || {};
  const imageButton = widget.type === "button" && dashboardButtonUsesImage(s);
  const accent =
    s.useSystemPalette === true
      ? systemPalette()[0]
      : s.color || systemPalette()[0] || "#35d4b0";
  const embedded = s.embeddedMode === true;
  const transparent = embedded || s.backgroundTransparent === true;
  const borderTransparent = embedded || s.borderTransparent === true;
  const borderWidth = Math.max(0, Math.min(16, Number(s.borderWidth) || 0));
  const borderStyle = runtimeBorderStyle(s.borderStyle);
  const borderColor = borderTransparent
    ? "transparent"
    : runtimeBorderColor(s, accent);
  const defaultPaddingY = embedded ? 0 : 13;
  const defaultPaddingX = embedded ? 0 : 14;
  const paddingTop = Math.max(
    0,
    Math.min(120, Number(s.contentPaddingTop ?? defaultPaddingY) || 0),
  );
  const paddingRight = Math.max(
    0,
    Math.min(120, Number(s.contentPaddingRight ?? defaultPaddingX) || 0),
  );
  const paddingBottom = Math.max(
    0,
    Math.min(120, Number(s.contentPaddingBottom ?? defaultPaddingY) || 0),
  );
  const paddingLeft = Math.max(
    0,
    Math.min(120, Number(s.contentPaddingLeft ?? defaultPaddingX) || 0),
  );
  return {
    left: `${l.x || 0}px`,
    top: `${l.y || 0}px`,
    width: `${l.w || 360}px`,
    height: `${l.h || 180}px`,
    zIndex: l.z || 1,
    transform: `rotate(${l.rotate || 0}deg)`,
    background: widget.type === "button" || transparent
      ? "transparent"
      : s.backgroundColor || "rgba(17,24,39,.84)",
    borderRadius: imageButton ? "0px" : `${s.borderRadius || 0}px`,
    opacity: s.opacity ?? 1,
    fontSize: `${Number(s.fontSize) || 16}px`,
    fontWeight: Number(s.fontWeight) || 400,
    textAlign: runtimeTextAlign(s.textAlign),
    border:
      widget.type === "button"
        ? "0 solid transparent"
        : borderWidth + "px " + borderStyle + " " + borderColor,
    padding: imageButton ? "0px" : `${paddingTop}px ${paddingRight}px ${paddingBottom}px ${paddingLeft}px`,
    boxShadow: ["button", "border"].includes(widget.type) ? "none" : undefined,
    "--accent": accent,
    "--widget-font-size": `${Number(s.fontSize) || 16}px`,
    "--widget-font-weight": Number(s.fontWeight) || 400,
    "--widget-text-align": runtimeTextAlign(s.textAlign),
    "--widget-content-justify": runtimeContentJustify(s.textAlign),
    "--widget-content-align-y": runtimeVerticalAlign(
      s.contentVerticalAlign,
    ),
    "--widget-border-color": borderColor,
    "--widget-border-width": borderWidth + "px",
    "--widget-border-style": borderStyle,
    "--widget-title-height": `${dashboardHeadingHeight(s, hasTitleImage(widget))}px`,
    "--flip-cell-gap": `${safeStyleNumber(s.flipCellGap, 0, 32, 8)}px`,
    "--flip-cell-width": `${safeStyleNumber(s.flipCellWidth, 18, 96, 42)}px`,
    "--flip-cell-height": `${safeStyleNumber(s.flipCellHeight, 24, 120, 58)}px`,
    "--flip-cell-background": s.flipCellBackground || "rgba(28,56,86,.72)",
    "--flip-cell-border": s.flipCellBorderColor || accent,
    "--stats-label-color": s.statsLabelColor || "#9aabbd",
    "--stats-label-size": `${Math.max(9, Math.min(36, Number(s.statsLabelSize) || 11))}px`,
    "--stats-unit-color": s.statsUnitColor || "#9aabbd",
    "--stats-unit-size": `${Math.max(9, Math.min(30, Number(s.statsUnitSize) || 12))}px`,
    "--button-padding-x": `${Math.max(0, Math.min(80, Number(s.buttonPaddingX ?? 22) || 0))}px`,
    "--button-padding-y": `${Math.max(0, Math.min(40, Number(s.buttonPaddingY ?? 8) || 0))}px`,
    "--image-fit": ["contain", "cover", "fill", "none", "scale-down"].includes(s.imageFit) ? s.imageFit : "contain",
    "--image-position": ["center", "top", "bottom", "left", "right"].includes(s.imagePosition) ? s.imagePosition : "center",
    "--timeline-done": s.timelineDoneColor || accent,
    "--timeline-active": s.timelineActiveColor || accent,
    "--access-avatar-size": `${Math.max(24, Math.min(72, Number(s.accessAvatarSize) || 56))}px`,
    "--access-row-height": `${Math.max(56, Math.min(120, Number(s.accessRowHeight) || 72))}px`,
    "--table-header-color": widget.type === "advanced-table" ? s.tableHeaderColor || "#8497aa" : accent,
    "--table-text-color": widget.type === "advanced-table" ? s.tableTextColor || "#cbd8e6" : accent,
    "--table-value-color": s.tableValueColor || s.tableTextColor || "#cbd8e6",
    "--table-header-background": s.tableHeaderBackground || "transparent",
    "--table-cell-padding": `${safeStyleNumber(s.tableCellPadding, 0, 32, 6)}px`,
    "--table-first-column-width": `${safeStyleNumber(s.tableFirstColumnWidth, 20, 90, 50)}%`,
  };
}
function runtimeTextAlign(value) {
  return ["left", "center", "right"].includes(value) ? value : "center";
}
function runtimeContentJustify(value) {
  return value === "left"
    ? "flex-start"
    : value === "right"
      ? "flex-end"
      : "center";
}
function runtimeVerticalAlign(value) {
  return value === "top"
    ? "flex-start"
    : value === "bottom"
      ? "flex-end"
      : "center";
}
function runtimeBorderStyle(value) {
  return ["solid", "dashed", "dotted", "double"].includes(value)
    ? value
    : "solid";
}
function runtimeBorderColor(style, fallback) {
  const color = style.borderColor || fallback || "#35d4b0";
  const opacity = Math.max(
    0,
    Math.min(1, Number(style.borderOpacity ?? 1)),
  );
  return opacity >= 1
    ? color
    : "color-mix(in srgb, " +
        color +
        " " +
        Math.round(opacity * 100) +
        "%, transparent)";
}
function widgetHeadingStyle(widget) {
  const s = widget.style || {};
  const imageRef = hasTitleImage(widget)
    ? dashboardResourceUrl(s.titleImageRef)
    : "";
  return {
    justifyContent:
      s.titleAlign === "right"
        ? "flex-end"
        : s.titleAlign === "center"
          ? "center"
          : "space-between",
    alignItems:
      s.titleVerticalAlign === "bottom"
        ? "flex-end"
        : s.titleVerticalAlign === "middle"
          ? "center"
          : "flex-start",
    textAlign: s.titleAlign || "left",
    color: s.titleColor || "#9cabbc",
    fontSize: `${Number(s.titleFontSize) || 12}px`,
    padding: `${Math.max(0, Math.min(120, Number(s.titlePaddingTop) || 0))}px ${Math.max(0, Math.min(120, Number(s.titlePaddingRight) || 0))}px ${Math.max(0, Math.min(120, Number(s.titlePaddingBottom) || 0))}px ${Math.max(0, Math.min(120, Number(s.titlePaddingLeft) || 0))}px`,
    backgroundImage: imageRef ? `url("${imageRef}")` : undefined,
    backgroundSize: imageRef ? titleImageSize(s.titleImageFit) : undefined,
    backgroundPosition: imageRef
      ? titleImagePosition(s.titleImageFit, s.titleImageAlign)
      : undefined,
    backgroundRepeat: imageRef ? "no-repeat" : undefined,
  };
}
function qualityLabel(quality) {
  return (
    {
      SUCCESS: "正常",
      NO_DATA: "无数据",
      NOT_CONNECTED: "未接入",
      INVALID_DATA: "数据异常",
      TIMEOUT: "超时",
      AUTH_ERROR: "鉴权失败",
      RATE_LIMITED: "请求受限",
      SOURCE_ERROR: "来源失败",
      CONNECT_ERROR: "连接失败",
      STALE: "已过期",
      FORBIDDEN: "无权限",
      DISABLED: "已停用",
    }[quality] || "未知"
  );
}
function qualityClass(quality) {
  return ["SUCCESS", "NO_DATA"].includes(quality)
    ? "quality-ok"
    : quality === "STALE"
      ? "quality-stale"
      : "quality-error";
}
function handleWidgetClick(widget, dataItem = null) {
  activeWidgetId.value = widget.id;
  const action =
    widget.interaction?.onClick || widget.interaction?.clickAction || "none";
  const item = dataItem || widgetStates[widget.id]?.rows?.[0] || null;
  if (action === "popup") {
    if (!item || typeof item !== "object")
      ElMessage.info("当前组件没有可展示的详情数据");
    else {
      detailPopup.widget = widget;
      detailPopup.row = item;
      const title = statisticsHeadingTitle(widget);
      detailPopup.title = title ? `${title} · 详情` : "数据详情";
      detailPopup.open = true;
    }
  }
  if (action === "self-drilldown") drilldownWidget(widget, item);
  if (
    (action === "page" || action === "drilldown") &&
    (widget.interaction?.target ||
      widget.interaction?.targetPageCode ||
      widget.interaction?.targetPageId)
  ) {
    const target = buildInteractionTarget(widget, item);
    if (target) router.push(target);
  }
  if (action === "browser" && widget.interaction?.target) {
    const target = safeBrowserUrl(widget.interaction.target);
    if (target) window.open(target, "_blank", "noopener,noreferrer");
    else ElMessage.warning("浏览器链接无效，仅允许 http:// 或 https:// 地址");
  }
  if (action === "filter") applyFilterInteraction(widget, item);
  if (window.parent !== window)
    postBridge("bridge:click", {
      componentId: widget.id,
      dataItem: item,
      action,
    });
}

function drilldownState(widget) {
  if (!drilldownStates[widget.id])
    drilldownStates[widget.id] = { level: 0, history: [] };
  return drilldownStates[widget.id];
}

async function drilldownWidget(widget, dataItem) {
  if (!dataItem || typeof dataItem !== "object") {
    ElMessage.info("当前组件没有可用于钻取的数据");
    return;
  }
  const dataset = datasetCatalog.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  );
  if (dataset?.dataType === "WEBSOCKET") {
    ElMessage.info("实时 WebSocket 组件不支持逐级钻取");
    return;
  }
  const levels = Array.isArray(widget.interaction?.drilldown?.levels)
    ? widget.interaction.drilldown.levels
    : [];
  const state = drilldownState(widget);
  const level = levels[state.level];
  if (!level) {
    ElMessage.info("已到达最末级");
    return;
  }
  const params = {};
  const filters = [];
  const declaredParams = parseResultArray(dataset?.paramSchemaJson)
    .map((item) => item.name)
    .filter(Boolean);
  const declaredFields = parseResultArray(dataset?.fieldSchemaJson)
    .map((item) => item.name)
    .filter(Boolean);
  const mappings = Array.isArray(level.parameterMappings)
    ? level.parameterMappings
    : [];
  mappings.forEach((mapping) => {
    if (
      !mapping?.sourceField ||
      dataItem[mapping.sourceField] === undefined ||
      dataItem[mapping.sourceField] === null
    )
      return;
    const value = dataItem[mapping.sourceField];
    if (declaredParams.includes(mapping.targetParameter))
      params[mapping.targetParameter] = value;
    else if (
      mapping.targetField &&
      declaredFields.includes(mapping.targetField)
    )
      filters.push({ field: mapping.targetField, operator: "eq", value });
  });
  if (!Object.keys(params).length && !filters.length) {
    ElMessage.warning("逐级钻取没有可用的参数或过滤映射");
    return;
  }
  state.history.push({
    level: state.level,
    linked: JSON.parse(JSON.stringify(linkedFilters[widget.id] || {})),
  });
  state.level += 1;
  linkedFilters[widget.id] = { params, filters };
  try {
    await loadWidget(widget);
    await nextTick(() => renderCharts([widget.id]));
  } catch {
    drilldownBack(widget);
  }
}

async function drilldownBack(widget) {
  const state = drilldownState(widget);
  const previous = state.history.pop();
  if (!previous) {
    state.level = 0;
    delete linkedFilters[widget.id];
  } else {
    state.level = previous.level;
    if (
      previous.linked &&
      (Object.keys(previous.linked.params || {}).length ||
        previous.linked.filters?.length)
    )
      linkedFilters[widget.id] = previous.linked;
    else delete linkedFilters[widget.id];
  }
  await loadWidget(widget);
  await nextTick(() => renderCharts([widget.id]));
}

function buildInteractionTarget(widget, dataItem) {
  const interaction = widget.interaction || {};
  const targetPageCode = String(interaction.targetPageCode || "").trim();
  const publishedMode = interaction.targetMode === "published";
  // 已发布页面模式不得回退到旧的任意 route；否则历史/直写配置可绕过
  // 服务端对 targetPageCode 的发布页面约束。
  if (publishedMode && !isSafePageCode(targetPageCode)) {
    // 登录态兼容极少数只保存 targetPageId 的旧配置；匿名分享必须有
    // 编码，才能让服务端按分享集合校验目标页面。
    const targetPageId = Number(interaction.targetPageId);
    if (
      isShareMode.value ||
      !Number.isSafeInteger(targetPageId) ||
      targetPageId <= 0
    )
      return "";
    const target = `/dashboard/runtime/${targetPageId}`;
    return appendInteractionParameters(target, interaction, dataItem);
  }
  // 匿名分享没有登录态，旧版站内路由（例如 /dashboard/runtime/2）既
  // 无法保证能访问，也可能把用户带回登录页；分享态只允许使用页面编码
  // 路由，由服务端按分享集合再次授权。
  if (isShareMode.value && !isSafePageCode(targetPageCode)) return "";
  const usePublishedPage = publishedMode || Boolean(targetPageCode);
  let target = usePublishedPage && isSafePageCode(targetPageCode)
    ? buildPublishedPageRoute(targetPageCode)
    : String(interaction.target || "");
  if (
    !target.startsWith("/") ||
    target.startsWith("//") ||
    target.includes("\\") ||
    target.includes("://") ||
    target.toLowerCase().includes("javascript:")
  )
    return "";
  return appendInteractionParameters(target, interaction, dataItem);
}

function appendInteractionParameters(target, interaction, dataItem) {
  const mappings = Array.isArray(interaction.parameterMappings)
    ? interaction.parameterMappings
    : [];
  if (!dataItem || typeof dataItem !== "object" || !mappings.length)
    return target;
  const query = mappings
    .filter(
      (item) =>
        item &&
        item.sourceField &&
        item.targetParameter &&
        dataItem[item.sourceField] !== undefined &&
        dataItem[item.sourceField] !== null,
    )
    .map(
      (item) =>
        `${encodeURIComponent(item.targetParameter)}=${encodeURIComponent(String(dataItem[item.sourceField]))}`,
    );
  if (!query.length) return target;
  return `${target}${target.includes("?") ? "&" : "?"}${query.join("&")}`;
}

function isSafePageCode(value) {
  return /^[a-z0-9][a-z0-9_-]{2,63}$/i.test(String(value || ""));
}

function buildPublishedPageRoute(pageCode) {
  const code = encodeURIComponent(String(pageCode || "").trim());
  if (!code) return "";
  if (isShareMode.value)
    return `/dashboard/share/${encodeURIComponent(shareToken.value)}/p/${code}`;
  if (isMenuRuntime.value) {
    if (route.meta?.dashboardRuntime) {
      const menuCode = encodeURIComponent(
        String(
          route.query?.dashboardPageCode ||
            route.meta.dashboardPageCode ||
            routePageCode.value ||
            "",
        ).trim(),
      );
      return `${route.path}?dashboardPageCode=${menuCode}&menuPageCode=${code}`;
    }
    return `/dashboard/runtime/code/${code}?menuRuntime=1`;
  }
  if (String(route.path || "").startsWith("/dashboard/runtime/")) {
    const params = new URLSearchParams();
    if (isPreview.value) params.set("preview", "1");
    if (revisionId.value) params.set("revisionId", String(revisionId.value));
    params.set("runtimePageCode", String(pageCode || "").trim());
    return `${route.path}?${params.toString()}`;
  }
  return `/dashboard/runtime/code/${code}`;
}

function safeBrowserUrl(value) {
  try {
    const target = new URL(String(value || "").trim());
    if (
      !["http:", "https:"].includes(target.protocol) ||
      target.username ||
      target.password ||
      !target.hostname
    )
      return "";
    return target.href;
  } catch {
    return "";
  }
}

async function applyFilterInteraction(sourceWidget, dataItem) {
  if (!dataItem || typeof dataItem !== "object") {
    ElMessage.info("当前组件没有可用于联动的数据");
    return;
  }
  const interaction = sourceWidget.interaction || {};
  const sourceField =
    interaction.sourceField ||
    sourceWidget.binding?.fieldMap?.category ||
    sourceWidget.binding?.fieldMap?.value ||
    Object.keys(dataItem)[0];
  if (!sourceField || dataItem[sourceField] === undefined) {
    ElMessage.warning("联动来源字段没有数据");
    return;
  }
  const targetIds = Array.isArray(interaction.targetWidgetIds)
    ? interaction.targetWidgetIds
    : [];
  if (!targetIds.length) {
    ElMessage.info("请在设计器中配置联动目标组件");
    return;
  }
  const sourceValue = dataItem[sourceField];
  await Promise.all(
    targetIds.map(async (targetId) => {
      const target = schema.value?.widgets?.find(
        (widget) => widget.id === targetId,
      );
      if (!target || !target.binding?.datasetCode) return;
      const dataset = datasetCatalog.value.find(
        (item) => item.datasetCode === target.binding.datasetCode,
      );
      const parameters = parseResultArray(dataset?.paramSchemaJson);
      const parameterName = interaction.targetParameter || sourceField;
      const hasParameter = parameters.some(
        (parameter) => parameter.name === parameterName,
      );
      const next = { params: {}, filters: [] };
      if (hasParameter) next.params[parameterName] = sourceValue;
      else
        next.filters.push({
          field: interaction.targetField || sourceField,
          operator: "eq",
          value: sourceValue,
        });
      linkedFilters[target.id] = next;
      await loadWidget(target);
    }),
  );
  await nextTick(() => renderCharts(targetIds));
}

function parseResultArray(value) {
  if (!value) return [];
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function tabItems(widget) {
  const raw = widget.style?.tabs || widget.style?.tabItems;
  if (Array.isArray(raw) && raw.length) return raw;
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.length) return parsed;
    } catch {
      /* invalid designer input falls back to labels */
    }
    const labels = raw
      .split(",")
      .map((item) => item.trim())
      .filter(Boolean);
    if (labels.length)
      return labels.map((label, index) => ({
        key: `tab-${index}`,
        label,
        content: "",
      }));
  }
  return [
    { key: "tab-0", label: "概览", content: "" },
    { key: "tab-1", label: "明细", content: "" },
  ];
}
function tabIndex(widget) {
  return Math.min(
    Number(tabValues[widget.id] || 0),
    Math.max(0, tabItems(widget).length - 1),
  );
}
function selectTab(widget, index) {
  tabValues[widget.id] = index;
  // 选项卡切换会让被隐藏的图表卸载、重新挂载。先释放旧实例，再在
  // DOM 更新后重新创建当前可见图表，避免切回选项卡后出现空白画布。
  disposeCharts();
  nextTick(() => {
    renderCharts();
    loadMapCharts(schema.value?.widgets || []);
  });
}
function formFields(widget) {
  const raw = widget.style?.formFields;
  if (Array.isArray(raw) && raw.length) return raw;
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed;
    } catch {
      /* invalid designer input uses default field */
    }
  }
  return [
    {
      name: "keyword",
      label: "关键词",
      parameter: "keyword",
      placeholder: "请输入关键词",
    },
  ];
}

function filterInputType(filter) {
  const type = String(filter?.type || "").toUpperCase();
  if (type === "NUMBER") return "number";
  if (type === "DATE") return "date";
  if (type === "DATETIME") return "datetime-local";
  return "text";
}

function formInputType(field) {
  const type = String(field?.type || "").toUpperCase();
  if (type === "NUMBER") return "number";
  if (type === "DATE") return "date";
  if (type === "DATETIME") return "datetime-local";
  return "text";
}

function formFieldOptions(field) {
  return dashboardFormOptions(field);
}
function submitFilterForm(widget) {
  formFields(widget).forEach((field) => {
    const filter = pageFilters.value.find(
      (item) =>
        item.parameter === field.parameter || item.id === field.filterId,
    );
    if (filter)
      pageFilterValues[filter.id] = formValues[widget.id]?.[field.name] ?? "";
  });
  changePageFilter();
}
function isStaticWidget(widget) {
  return [
    "text",
    "rich-text",
    "icon",
    "button",
    "image",
    "video",
    "iframe",
    "custom-html",
    "current-time",
    "color-block",
    "border",
    "decoration",
    "tabs",
    "filter-form",
    "designer-form",
    "online-form",
  ].includes(widget.type);
}
function clearActiveWidget() {
  activeWidgetId.value = "";
}
function disposeCharts() {
  Object.keys(chartInstances).forEach((key) => {
    chartInstances[key].dispose();
    delete chartInstances[key];
  });
  Object.keys(mapInstances).forEach((key) => {
    mapInstances[key].dispose();
    delete mapInstances[key];
  });
  Object.keys(chartElements).forEach((key) => delete chartElements[key]);
  Object.keys(mapElements).forEach((key) => delete mapElements[key]);
  Object.keys(mapStates).forEach((key) => delete mapStates[key]);
  clearIncrementalChartCaches();
}
function closeWidgetSocket(id) {
  const socket = wsConnections[id];
  delete wsConnections[id];
  socketRequestKeys.delete(id);
  window.clearTimeout(socketRefreshTimers.get(id));
  socketRefreshTimers.delete(id);
  window.clearTimeout(wsReconnectTimers[id]);
  delete wsReconnectTimers[id];
  delete wsReconnectAttempts[id];
  socket?.close();
}
function closeSockets() {
  Object.keys(wsConnections).forEach(closeWidgetSocket);
  Object.values(wsReconnectTimers).forEach(timer => window.clearTimeout(timer));
  Object.keys(wsReconnectTimers).forEach(key => delete wsReconnectTimers[key]);
  Object.keys(wsReconnectAttempts).forEach(key => delete wsReconnectAttempts[key]);
  for (const timer of socketRefreshTimers.values()) window.clearTimeout(timer);
  socketRefreshTimers.clear();
  socketRequestKeys.clear();
}
function closeCustomFrames() {
  Object.values(customLoadingTimers).forEach((timer) =>
    window.clearTimeout(timer),
  );
  Object.keys(customLoadingTimers).forEach(
    (key) => delete customLoadingTimers[key],
  );
  Object.keys(customLoading).forEach((key) => delete customLoading[key]);
  Object.keys(customFrameRefs).forEach((key) => delete customFrameRefs[key]);
  Object.keys(customFrameWindows).forEach(
    (key) => delete customFrameWindows[key],
  );
  Object.keys(customFrameNonces).forEach(
    (key) => delete customFrameNonces[key],
  );
}
const carouselOffsets = reactive({});
const advancedOffsets = reactive({});
const carouselTimers = {};
function resetCarouselTimers(widgets) {
  Object.values(carouselTimers).forEach((timer) => window.clearInterval(timer));
  Object.keys(carouselTimers).forEach((key) => delete carouselTimers[key]);
  widgets
    .filter((widget) => ["carousel-table", "carousel"].includes(widget.type))
    .forEach((widget) => {
      carouselOffsets[widget.id] = 0;
      const seconds = Math.max(
        2,
        Number(
          widget.style?.carouselInterval || widget.style?.carouselSeconds,
        ) || 4,
      );
      carouselTimers[widget.id] = window.setInterval(() => {
        carouselOffsets[widget.id] =
          Number(carouselOffsets[widget.id] || 0) + 1;
      }, seconds * 1000);
    });
  widgets
    .filter(
      (widget) =>
        widget.type === "advanced-table" &&
        widget.style?.advancedScroll !== false,
    )
    .forEach((widget) => {
      advancedOffsets[widget.id] = 0;
      const seconds = Math.max(3, Number(widget.style?.carouselInterval || 5));
      carouselTimers[widget.id] = window.setInterval(() => {
        advancedOffsets[widget.id] =
          Number(advancedOffsets[widget.id] || 0) + 1;
      }, seconds * 1000);
    });
}
function ensureStageResizeObserver() {
  if (observedStageViewport === stageViewport.value) return;
  stageResizeObserver?.disconnect();
  stageResizeObserver = undefined;
  observedStageViewport = stageViewport.value;
  if (typeof ResizeObserver === "undefined" || !observedStageViewport) return;
  stageResizeObserver = new ResizeObserver(resizeCharts);
  stageResizeObserver.observe(observedStageViewport);
}
watch(stageViewport, ensureStageResizeObserver, { flush: 'post' });

function resizeCharts() {
  ensureStageResizeObserver();
  Object.values(chartInstances).forEach((chart) => chart.resize());
  Object.values(mapInstances).forEach((chart) => chart.resize());
  fitStage();
}
function fitStage() {
  const el = stageViewport.value;
  if (!el) return;
  const inset =
    isEmbedMode.value || isShareMode.value || isFullscreen.value ? 0 : 44;
  const availableWidth = Math.max(1, el.clientWidth - inset);
  const availableHeight = Math.max(1, el.clientHeight - inset);
  if (isFullscreen.value) {
    const scale = calculateFullscreenScale(
      canvasWidth.value,
      canvasHeight.value,
      availableWidth,
      availableHeight,
      normalizeFullscreenScaleMode(schema.value?.canvas),
    );
    stageScaleX.value = scale.x;
    stageScaleY.value = scale.y;
    stageScale.value = Math.min(scale.x, scale.y);
    return;
  }
  const scaleX = Math.max(0.2, Math.min(4, availableWidth / canvasWidth.value));
  const scaleY = Math.max(
    0.2,
    Math.min(4, availableHeight / canvasHeight.value),
  );
  if (schema.value?.canvas?.scaleMode === "stretch") {
    stageScaleX.value = scaleX;
    stageScaleY.value = scaleY;
    stageScale.value = Math.min(scaleX, scaleY);
    return;
  }
  const uniformScale = Math.min(
    1,
    Math.max(0.2, Math.floor(Math.min(scaleX, scaleY) * 100) / 100),
  );
  stageScale.value = uniformScale;
  stageScaleX.value = uniformScale;
  stageScaleY.value = uniformScale;
}
function fullscreenElement() {
  return document.fullscreenElement || document.webkitFullscreenElement || null;
}
async function toggleFullscreen() {
  const target = runtimePage.value;
  if (isFullscreen.value) {
    const nativeFullscreen = fullscreenElement();
    fullscreenFallback.value = false;
    isFullscreen.value = false;
    nextTick(() => {
      stageViewport.value?.scrollTo({ top: 0, left: 0 });
      resizeCharts();
    });
    if (!nativeFullscreen) return;
    try {
      if (document.exitFullscreen) await document.exitFullscreen();
      else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
    } catch (error) {
      ElMessage.error(error?.message || "退出全屏失败");
    }
    return;
  }
  fullscreenFallback.value = true;
  isFullscreen.value = true;
  nextTick(() => {
    stageViewport.value?.scrollTo({ top: 0, left: 0 });
    resizeCharts();
  });
  const requestFullscreen =
    target?.requestFullscreen || target?.webkitRequestFullscreen;
  if (!requestFullscreen) return;
  try {
    await requestFullscreen.call(target);
  } catch (error) {
    ElMessage.warning(
      error?.message || "浏览器原生全屏不可用，已切换为页面全屏",
    );
  }
}
function handleFullscreenChange() {
  const active = fullscreenElement() === runtimePage.value;
  if (active) fullscreenFallback.value = false;
  if (!fullscreenFallback.value) isFullscreen.value = active;
  nextTick(resizeCharts);
}
function handleFullscreenKeydown(event) {
  if (event.key !== "Escape" || !fullscreenFallback.value) return;
  fullscreenFallback.value = false;
  isFullscreen.value = false;
  nextTick(resizeCharts);
}
function back() {
  revisionId.value
    ? router.push("/dashboard/page")
    : isPreview.value
      ? designer()
      : router.push("/dashboard/page");
}
function designer() {
  router.push(`/dashboard/designer/${pageId}`);
}

const CUSTOM_HTML_BRIDGE_SCRIPT = [
  "(function(){",
  "  var PROTOCOL='dashboard-custom-html', VERSION=1, handlers=[], pending={}, lastRows=null, sequence=0;",
  "  function clone(value){ try { return JSON.parse(JSON.stringify(value)); } catch(e) { return null; } }",
  "  function send(type,payload,requestId){ parent.postMessage({ protocol:PROTOCOL, version:VERSION, source:'custom-html', type:type, pageId:window.__DASHBOARD_PAGE_ID__, widgetId:window.__DASHBOARD_WIDGET_ID__, nonce:window.__DASHBOARD_FRAME_NONCE__, requestId:requestId||null, payload:clone(payload) }, '*'); }",
  "  function request(type,payload){ return new Promise(function(resolve,reject){ var id='custom-'+Date.now()+'-'+(++sequence); var timer=setTimeout(function(){ delete pending[id]; reject(new Error('JLink 请求超时')); },3000); pending[id]={resolve:resolve,reject:reject,timer:timer}; send(type,payload,id); }); }",
  "  function notify(rows){ lastRows=Array.isArray(rows)?clone(rows):[]; handlers.slice().forEach(function(callback){ try { callback(clone(lastRows)); } catch(e) {} }); }",
  "  window.addEventListener('message',function(event){ var message=event.data||{}; if(event.source!==parent || message.protocol!==PROTOCOL || Number(message.version)!==VERSION || message.source!=='runtime') return; if(message.type==='init' || message.type==='data'){ var payload=message.payload||{}; notify(payload.rows); return; } if(message.type==='response' && message.requestId && pending[message.requestId]){ var task=pending[message.requestId]; delete pending[message.requestId]; clearTimeout(task.timer); if(message.ok===false) task.reject(new Error(message.error||'JLink 操作失败')); else task.resolve(clone(message.payload)); } });",
  "  window.JLink={",
  "    onData:function(callback){ if(typeof callback!=='function') return function(){}; handlers.push(callback); if(lastRows!==null) setTimeout(function(){ try{ callback(clone(lastRows)); }catch(e){} },0); return function(){ handlers=handlers.filter(function(item){return item!==callback;}); }; },",
  "    linkage:function(payload){ return request('linkage',payload||{}); },",
  "    drill:function(payload){ return request('drill',payload||{}); },",
  "    get:function(id){ return request('get',{id:id}); },",
  "    update:function(id,patch){ return request('update',{id:id,patch:patch||{}}); },",
  "    hide:function(id){ return request('update',{id:id,patch:{state:{visible:false}}}); },",
  "    show:function(id){ return request('update',{id:id,patch:{state:{visible:true}}}); },",
  "    move:function(id,x,y){ return request('move',{id:id,x:x,y:y}); },",
  "    resize:function(id,w,h){ return request('resize',{id:id,w:w,h:h}); },",
  "    setConfig:function(id,patch){ return request('update',{id:id,patch:patch||{}}); },",
  "    setData:function(id,rows){ return request('setData',{id:id,rows:Array.isArray(rows)?rows:[]}); },",
  "    reload:function(id){ return request('reload',{id:id}); },",
  "    loading:function(id,duration){ return request('loading',{id:id,duration:duration}); },",
  "    emit:function(payload,type){ return request(type||'linkage',payload||{}); }",
  "  };",
  "  send('ready',{});",
  "})();",
].join("\n");

function customHtmlSrcdoc(widget) {
  const fallback =
    '<div style="height:100%;display:flex;align-items:center;justify-content:center;color:#35d4b0;font:600 18px sans-serif">自定义内容组件</div>';
  const raw = String(widget?.style?.htmlContent || fallback);
  const html = raw
    .replace(/<!doctype[^>]*>/gi, "")
    .replace(/<\/?(?:html|head|body)(?:\s[^>]*)?>/gi, "");
  const csp =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: blob: /profile/ /dashboard/assets/ /static/; font-src data: blob: /profile/ /dashboard/assets/ /static/; media-src data: blob: /profile/ /dashboard/assets/ /static/; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'>";
  // Avoid writing a literal closing script tag in this SFC source: Vue's
  // template parser would treat it as the end of the surrounding script block.
  const scriptEnd = "<" + "/script>";
  const nonce = customFrameNonce(widget.id);
  const context =
    "<script>window.__DASHBOARD_PAGE_ID__=" +
    JSON.stringify(pageId) +
    ";window.__DASHBOARD_WIDGET_ID__=" +
    JSON.stringify(widget.id) +
    ";window.__DASHBOARD_FRAME_NONCE__=" +
    JSON.stringify(nonce) +
    ";" +
    scriptEnd;
  const bridge = "<script>" + CUSTOM_HTML_BRIDGE_SCRIPT + scriptEnd;
  return (
    '<!doctype html><html><head><meta charset="UTF-8">' +
    csp +
    "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}*{box-sizing:border-box}</style>" +
    context +
    bridge +
    "</head><body>" +
    html +
    "</body></html>"
  );
}

function setCustomFrameRef(id, element) {
  if (element) customFrameRefs[id] = element;
  else {
    delete customFrameRefs[id];
    delete customFrameWindows[id];
    delete customFrameNonces[id];
  }
}

function customFrameNonce(id) {
  if (!customFrameNonces[id]) {
    const random =
      typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    customFrameNonces[id] = random;
  }
  return customFrameNonces[id];
}

function customFrameElement(id) {
  return (
    Array.from(
      document.querySelectorAll("iframe[data-custom-html-widget]"),
    ).find(
      (frame) => frame.getAttribute("data-custom-html-widget") === String(id),
    ) || null
  );
}

function customFrameWindow(frame) {
  if (!frame) return null;
  if (frame.contentWindow) return frame.contentWindow;
  const index = Array.from(document.querySelectorAll("iframe")).indexOf(frame);
  return index >= 0 ? window.frames[index] : null;
}

function customFrameLoaded(widget) {
  const frame = customFrameRefs[widget.id] || customFrameElement(widget.id);
  const frameWindow = customFrameWindow(frame);
  if (!frameWindow) return;
  customFrameNonces[widget.id] =
    frame.getAttribute("data-custom-html-nonce") || customFrameNonce(widget.id);
  customFrameWindows[widget.id] = frameWindow;
  postCustomMessage(widget.id, "init", {
    rows: widgetStates[widget.id]?.rows || [],
    result: widgetStates[widget.id] || null,
    components: (schema.value?.widgets || [])
      .map(publicCustomWidget)
      .filter(Boolean),
  });
}

function postCustomMessage(
  widgetId,
  type,
  payload = {},
  requestId = null,
  error = null,
) {
  const target = customFrameWindows[widgetId];
  if (!target) return false;
  try {
    target.postMessage(
      {
        protocol: CUSTOM_HTML_PROTOCOL,
        version: CUSTOM_HTML_VERSION,
        source: "runtime",
        type,
        pageId,
        widgetId,
        nonce: customFrameNonce(widgetId),
        requestId,
        ok: !error,
        error,
        payload,
      },
      "*",
    );
    return true;
  } catch {
    return false;
  }
}

function notifyCustomData(widget) {
  if (widget?.type !== "custom-html") return;
  postCustomMessage(widget.id, "data", {
    rows: widgetStates[widget.id]?.rows || [],
    result: widgetStates[widget.id] || null,
  });
}

function publicCustomWidget(widget) {
  if (!widget || typeof widget !== "object") return null;
  return {
    id: widget.id,
    type: widget.type,
    name:
      widget.name ||
      widget.style?.title ||
      typeLabels[widget.type] ||
      widget.type,
    layout: { ...(widget.layout || {}) },
    state: {
      visible: widget.state?.visible !== false,
      locked: widget.state?.locked === true,
    },
    style: {
      title: widget.style?.title || "",
      titleVisible: widget.style?.titleVisible !== false,
      color: widget.style?.color || "",
      backgroundColor: widget.style?.backgroundColor || "",
      opacity: widget.style?.opacity ?? 1,
      borderRadius: widget.style?.borderRadius || 0,
      fontSize: widget.style?.fontSize || 16,
      textAlign: widget.style?.textAlign || "center",
    },
    binding: {
      sourceType: widget.binding?.sourceType || "DATASET",
      datasetCode: widget.binding?.datasetCode || "",
      fieldMap: { ...(widget.binding?.fieldMap || {}) },
      displayFields: Array.isArray(widget.binding?.displayFields)
        ? [...widget.binding.displayFields]
        : [],
      rowLimit: widget.binding?.rowLimit || 50,
    },
  };
}

function runtimeWidget(id) {
  return schema.value?.widgets?.find((widget) => widget.id === id) || null;
}
function customSafeNumber(value, fallback, min, max) {
  const number = Number(value);
  return Number.isFinite(number)
    ? Math.max(min, Math.min(max, number))
    : fallback;
}
function customText(value, field, max = 256) {
  if (
    typeof value !== "string" ||
    value.length > max ||
    /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value)
  )
    throw new Error(`${field} 内容不合法`);
  return value;
}
function customColor(value, field) {
  const color = customText(value, field, 64).trim();
  if (
    color &&
    !/^(?:#[0-9a-f]{3,8}|rgba?\([0-9 .,%-]+\)|hsla?\([0-9 .,%-]+\)|var\(--[a-z0-9_-]+\)|transparent)$/i.test(
      color,
    )
  )
    throw new Error(`${field} 颜色格式不合法`);
  return color;
}

function updateCustomWidget(id, patch = {}) {
  const widget = runtimeWidget(id);
  if (!widget || !patch || typeof patch !== "object" || Array.isArray(patch))
    throw new Error("目标组件不存在或修改内容不合法");
  if (
    patch.state &&
    typeof patch.state === "object" &&
    !Array.isArray(patch.state)
  ) {
    if (Object.prototype.hasOwnProperty.call(patch.state, "visible"))
      widget.state = {
        ...(widget.state || {}),
        visible: Boolean(patch.state.visible),
      };
    if (Object.prototype.hasOwnProperty.call(patch.state, "locked"))
      widget.state = {
        ...(widget.state || {}),
        locked: Boolean(patch.state.locked),
      };
  }
  if (
    patch.layout &&
    typeof patch.layout === "object" &&
    !Array.isArray(patch.layout)
  ) {
    const layout = { ...(widget.layout || {}) };
    const width = canvasWidth.value;
    const height = canvasHeight.value;
    layout.w = customSafeNumber(
      patch.layout.w,
      Number(layout.w) || 40,
      40,
      width,
    );
    layout.h = customSafeNumber(
      patch.layout.h,
      Number(layout.h) || 40,
      40,
      height,
    );
    layout.x = customSafeNumber(
      patch.layout.x,
      Number(layout.x) || 0,
      0,
      Math.max(0, width - layout.w),
    );
    layout.y = customSafeNumber(
      patch.layout.y,
      Number(layout.y) || 0,
      0,
      Math.max(0, height - layout.h),
    );
    if (Object.prototype.hasOwnProperty.call(patch.layout, "rotate"))
      layout.rotate = customSafeNumber(patch.layout.rotate, 0, -360, 360);
    widget.layout = layout;
  }
  if (
    patch.style &&
    typeof patch.style === "object" &&
    !Array.isArray(patch.style)
  ) {
    const allowed = [
      "title",
      "titleVisible",
      "subtitle",
      "color",
      "backgroundColor",
      "opacity",
      "borderRadius",
      "fontSize",
      "fontWeight",
      "textAlign",
    ];
    const nextStyle = { ...(widget.style || {}) };
    if (Object.prototype.hasOwnProperty.call(patch.style, "title"))
      nextStyle.title = customText(patch.style.title, "标题");
    if (Object.prototype.hasOwnProperty.call(patch.style, "titleVisible")) {
      if (typeof patch.style.titleVisible !== "boolean")
        throw new Error("标题显隐值不合法");
      nextStyle.titleVisible = patch.style.titleVisible;
    }
    if (Object.prototype.hasOwnProperty.call(patch.style, "subtitle"))
      nextStyle.subtitle = customText(patch.style.subtitle, "副标题");
    if (Object.prototype.hasOwnProperty.call(patch.style, "color"))
      nextStyle.color = customColor(patch.style.color, "文字");
    if (Object.prototype.hasOwnProperty.call(patch.style, "backgroundColor"))
      nextStyle.backgroundColor = customColor(
        patch.style.backgroundColor,
        "背景",
      );
    if (Object.prototype.hasOwnProperty.call(patch.style, "opacity"))
      nextStyle.opacity = customSafeNumber(patch.style.opacity, 1, 0, 1);
    if (Object.prototype.hasOwnProperty.call(patch.style, "borderRadius"))
      nextStyle.borderRadius = customSafeNumber(
        patch.style.borderRadius,
        0,
        0,
        64,
      );
    if (Object.prototype.hasOwnProperty.call(patch.style, "fontSize"))
      nextStyle.fontSize = customSafeNumber(patch.style.fontSize, 16, 8, 96);
    if (Object.prototype.hasOwnProperty.call(patch.style, "fontWeight"))
      nextStyle.fontWeight = customSafeNumber(
        patch.style.fontWeight,
        400,
        100,
        900,
      );
    if (Object.prototype.hasOwnProperty.call(patch.style, "textAlign")) {
      if (!["left", "center", "right"].includes(patch.style.textAlign))
        throw new Error("文字对齐方式不合法");
      nextStyle.textAlign = patch.style.textAlign;
    }
    widget.style = nextStyle;
  }
  return publicCustomWidget(widget);
}

function customRows(value) {
  if (!Array.isArray(value) || value.length > 1000)
    throw new Error("组件数据必须是 0-1000 行数组");
  let serialized;
  try {
    serialized = JSON.stringify(value);
  } catch {
    throw new Error("组件数据必须是可序列化 JSON");
  }
  if (typeof serialized !== "string" || serialized.length > 512 * 1024)
    throw new Error("组件数据不能超过 512 KB");
  return value.map((row) => {
    if (!row || typeof row !== "object" || Array.isArray(row))
      throw new Error("组件数据每一行必须是对象");
    const keys = Object.keys(row);
    if (keys.length > 100) throw new Error("单行组件数据字段不能超过 100 个");
    if (
      keys.some(
        (key) =>
          key === "__proto__" ||
          key === "constructor" ||
          key === "prototype" ||
          key.length > 128,
      )
    )
      throw new Error("组件数据字段名不合法");
    try {
      return JSON.parse(JSON.stringify(row));
    } catch {
      throw new Error("组件数据必须是可序列化 JSON");
    }
  });
}

async function handleCustomMessage(event) {
  const message = event.data || {};
  const widgetId = message.widgetId;
  if (
    Number(message.pageId) !== pageId ||
    message.protocol !== CUSTOM_HTML_PROTOCOL ||
    Number(message.version) !== CUSTOM_HTML_VERSION ||
    message.source !== "custom-html" ||
    message.nonce !== customFrameNonces[widgetId]
  )
    return;
  const widget = runtimeWidget(widgetId);
  if (!widget || widget.type !== "custom-html") return;
  const frame = customFrameWindows[widgetId];
  // A sandbox iframe can post its ready message before Vue's @load handler
  // has run. Bind the source on the first handshake, but only if it is the
  // contentWindow belonging to the rendered iframe ref.
  if (message.type === "ready") {
    const frameElement =
      customFrameRefs[widgetId] || customFrameElement(widgetId);
    if (!frameElement || !event.source) return;
    const frameNonce = frameElement.getAttribute("data-custom-html-nonce");
    if (!frameNonce || message.nonce !== frameNonce) return;
    customFrameNonces[widgetId] = frameNonce;
    const expected = customFrameWindow(frameElement);
    if (expected && event.source !== expected) return;
    customFrameWindows[widgetId] = event.source;
    customFrameLoaded(widget);
    return;
  }
  if (!frame || event.source !== frame) return;
  const payload = message.payload;
  const respond = (ok, value, error) =>
    postCustomMessage(
      widgetId,
      "response",
      ok ? value : {},
      message.requestId,
      ok ? null : error,
    );
  try {
    if (message.type === "linkage") {
      await applyFilterInteraction(
        widget,
        payload && typeof payload === "object" ? payload : {},
      );
      respond(true, null);
      return;
    }
    if (message.type === "drill") {
      await drilldownWidget(
        widget,
        payload && typeof payload === "object" ? payload : {},
      );
      respond(true, null);
      return;
    }
    if (message.type === "get") {
      const target = runtimeWidget(payload?.id);
      if (!target) throw new Error("目标组件不存在");
      respond(true, publicCustomWidget(target));
      return;
    }
    if (message.type === "update") {
      respond(true, updateCustomWidget(payload?.id, payload?.patch));
      await nextTick(() => {
        renderCharts();
        const target = runtimeWidget(payload?.id);
        if (target && isMapWidget(target.type)) loadMapCharts([target]);
      });
      return;
    }
    if (message.type === "move") {
      const target = runtimeWidget(payload?.id);
      if (!target) throw new Error("目标组件不存在");
      respond(
        true,
        updateCustomWidget(target.id, {
          layout: { x: payload.x, y: payload.y },
        }),
      );
      return;
    }
    if (message.type === "resize") {
      const target = runtimeWidget(payload?.id);
      if (!target) throw new Error("目标组件不存在");
      respond(
        true,
        updateCustomWidget(target.id, {
          layout: { w: payload.w, h: payload.h },
        }),
      );
      return;
    }
    if (message.type === "setData") {
      const target = runtimeWidget(payload?.id);
      if (!target) throw new Error("目标组件不存在");
      overrideData[target.id] = customRows(payload?.rows);
      widgetStates[target.id] = normalizeResult(
        target.binding?.datasetCode,
        overrideData[target.id],
      );
      notifyCustomData(target);
      await nextTick(() => {
        renderCharts();
        if (isMapWidget(target.type)) loadMapCharts([target]);
      });
      respond(true, publicCustomWidget(target));
      return;
    }
    if (message.type === "reload") {
      const target = runtimeWidget(payload?.id);
      if (!target) throw new Error("目标组件不存在");
      delete overrideData[target.id];
      await loadWidget(target);
      await nextTick(() => {
        renderCharts();
        if (isMapWidget(target.type)) loadMapCharts([target]);
      });
      respond(true, widgetStates[target.id] || null);
      return;
    }
    if (message.type === "loading") {
      const target = runtimeWidget(payload?.id);
      if (!target) throw new Error("目标组件不存在");
      const duration = payload?.duration;
      window.clearTimeout(customLoadingTimers[target.id]);
      if (duration === false || Number(duration) <= 0)
        delete customLoading[target.id];
      else {
        customLoading[target.id] = true;
        if (Number.isFinite(Number(duration)) && Number(duration) > 0)
          customLoadingTimers[target.id] = window.setTimeout(
            () => {
              delete customLoading[target.id];
            },
            Math.min(60000, Number(duration)),
          );
      }
      respond(true, true);
      return;
    }
    if (message.type === "emit") {
      const emitType = payload?.type || "linkage";
      if (emitType === "drill")
        await drilldownWidget(widget, payload?.payload || {});
      else if (emitType === "linkage")
        await applyFilterInteraction(widget, payload?.payload || {});
      else throw new Error("JLink 事件类型不支持");
      respond(true, null);
    }
  } catch (error) {
    respond(false, null, error?.message || "JLink 操作失败");
  }
}

function bridgeOrigin() {
  try {
    return document.referrer
      ? new URL(document.referrer).origin
      : window.location.origin;
  } catch {
    return window.location.origin;
  }
}
function postBridge(type, payload = {}) {
  if (window.parent === window) return;
  const target = bridgeOrigin();
  const body = { pageId, ...payload };
  window.parent.postMessage(
    {
      protocol: BRIDGE_PROTOCOL,
      version: BRIDGE_VERSION,
      channel: channel.value || "default",
      source: "bridge",
      type,
      payload: body,
      pageId,
      ...payload,
      ts: Date.now(),
    },
    target,
  );
}
function onMessage(event) {
  if (event.data?.protocol === CUSTOM_HTML_PROTOCOL) {
    handleCustomMessage(event);
    return;
  }
  if (event.origin !== bridgeOrigin()) return;
  const data = event.data;
  if (
    !data ||
    typeof data !== "object" ||
    data.protocol !== BRIDGE_PROTOCOL ||
    Number(data.version) !== BRIDGE_VERSION ||
    data.source !== "host"
  )
    return;
  if ((data.channel || "default") !== (channel.value || "default")) return;
  const payload =
    data.payload && typeof data.payload === "object" ? data.payload : data;
  if (
    Number(payload.pageId ?? data.pageId) !== pageId ||
    data.type !== "host:component-update"
  )
    return;
  const widgetId =
    payload.componentId ||
    payload.widgetId ||
    data.componentId ||
    data.widgetId;
  if (!widgetId) return;
  const value = Object.prototype.hasOwnProperty.call(payload, "data")
    ? payload.data
    : data.data;
  const target = schema.value?.widgets?.find(
    (widget) => widget.id === widgetId,
  );
  if (!target) {
    postBridge("bridge:component-updated", {
      componentId: widgetId,
      ok: false,
      error: "no-instance",
    });
    return;
  }
  if (value === null) {
    delete overrideData[widgetId];
    refreshData({ widgets: [target], checkConfiguration: false }).then(() =>
      postBridge("bridge:component-updated", {
        componentId: widgetId,
        ok: true,
      }),
    );
  } else {
    overrideData[widgetId] = value;
    widgetStates[widgetId] = normalizeResult(
      target.binding?.datasetCode,
      value,
    );
    nextTick(renderCharts).then(() =>
      postBridge("bridge:component-updated", {
        componentId: widgetId,
        ok: true,
      }),
    );
  }
}

function handleRuntimeDataRefresh(event) {
  if (event.detail?.path === route.path) refreshData();
}

let previousBodyBackground = "";
let previousDocumentBackground = "";
watch(
  () => route.fullPath,
  (nextPath, previousPath) => {
    if (!nextPath || nextPath === previousPath) return;
    // Vue Router 会复用同一个运行组件实例；页面编码、分享令牌或查询
    // 参数变化时必须重新同步 pageId 并重建数据/图表/WebSocket 状态。
    load();
  },
);
onMounted(() => {
  runtimeActive = true;
  if (isFullscreenPreview.value) {
    fullscreenFallback.value = true;
    isFullscreen.value = true;
  }
  if (isEmbedMode.value) {
    previousBodyBackground = document.body.style.backgroundColor;
    previousDocumentBackground = document.documentElement.style.backgroundColor;
    document.body.style.backgroundColor = "transparent";
    document.documentElement.style.backgroundColor = "transparent";
    document.documentElement.classList.add("dashboard-embed");
  }
  clockTimer = window.setInterval(() => {
    clockNow.value = new Date();
  }, 1000);
  load();
  fitStage();
  if (isEmbedMode.value) {
    nextTick(() => {
      window.requestAnimationFrame(resizeCharts);
      window.setTimeout(resizeCharts, 360);
    });
  }
  window.addEventListener("resize", resizeCharts);
  window.addEventListener("dashboard:refresh-data", handleRuntimeDataRefresh);
  window.addEventListener("message", onMessage);
  window.addEventListener("keydown", handleFullscreenKeydown);
  document.addEventListener("fullscreenchange", handleFullscreenChange);
  document.addEventListener("webkitfullscreenchange", handleFullscreenChange);
  document.addEventListener("visibilitychange", handleShareVisibilityChange);
  ensureStageResizeObserver();
  nextTick(ensureStageResizeObserver);
});
onActivated(() => {
  runtimeActive = true;
  if (isShareMode.value && schema.value) checkShareVersion();
});
onDeactivated(() => {
  runtimeActive = false;
  stopShareVersionCheck();
});
onBeforeUnmount(() => {
  runtimeActive = false;
  stopShareVersionCheck();
  clearRefreshTimer();
  if (clockTimer) window.clearInterval(clockTimer);
  Object.values(widgetRefreshTimers).forEach((timer) =>
    window.clearInterval(timer),
  );
  Object.values(carouselTimers).forEach((timer) => window.clearInterval(timer));
  disposeCharts();
  closeSockets();
  closeCustomFrames();
  stageResizeObserver?.disconnect();
  stageResizeObserver = undefined;
  ++loadGeneration;
  ++requestGeneration;
  widgetRequests.clear();
  window.removeEventListener("dashboard:refresh-data", handleRuntimeDataRefresh);
  window.removeEventListener("resize", resizeCharts);
  window.removeEventListener("message", onMessage);
  window.removeEventListener("keydown", handleFullscreenKeydown);
  document.removeEventListener("fullscreenchange", handleFullscreenChange);
  document.removeEventListener("visibilitychange", handleShareVisibilityChange);
  document.removeEventListener(
    "webkitfullscreenchange",
    handleFullscreenChange,
  );
  if (fullscreenElement() === runtimePage.value) {
    const exit = document.exitFullscreen || document.webkitExitFullscreen;
    if (exit) Promise.resolve(exit.call(document)).catch(() => {});
  }
  if (isEmbedMode.value) {
    document.body.style.backgroundColor = previousBodyBackground;
    document.documentElement.style.backgroundColor = previousDocumentBackground;
    document.documentElement.classList.remove("dashboard-embed");
  }
});
</script>

<style scoped>
.runtime-route-root {
  min-height: 100%;
  height: 100%;
}
.runtime-loading-mask {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(7, 14, 25, 0.58);
  color: #c8d6e5;
  font-size: 13px;
  pointer-events: all;
}
.runtime-page {
  min-height: 100vh;
  height: 100vh;
  overflow: hidden;
  background: #0c1422;
  color: #e5edf6;
  display: flex;
  flex-direction: column;
}
.runtime-page.embed-mode {
  background: transparent;
}
.runtime-toolbar {
  height: 56px;
  flex: 0 0 56px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  background: #121c2b;
  border-bottom: 1px solid #253449;
}
.runtime-title,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.runtime-title {
  min-width: 0;
}
.runtime-title :deep(.el-button) {
  color: #9aabbd;
}
.toolbar-divider {
  height: 18px;
  width: 1px;
  background: #344358;
}
.page-name {
  font-size: 16px;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.toolbar-right {
  color: #8fa0b3;
  font-size: 11px;
}
.toolbar-right :deep(.el-button) {
  color: #9caec0;
}
.preview-note {
  color: #e8b660;
  display: inline-flex;
  gap: 5px;
  align-items: center;
}
.stage-viewport {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 22px;
  background: radial-gradient(
    circle at 50% 35%,
    rgba(30, 54, 81, 0.18),
    transparent 50%
  );
}
.stage-viewport.embed-viewport {
  padding: 0;
  align-items: flex-start;
  background: transparent;
}
.stage-viewport.hide-scrollbar {
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.stage-viewport.hide-scrollbar::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}
.stage-holder {
  position: relative;
  flex: 0 0 auto;
}
.runtime-stage {
  position: relative;
  transform-origin: top left;
  overflow: hidden;
  box-shadow: 0 20px 70px rgba(0, 0, 0, 0.38);
  border-radius: 3px;
  background-repeat: no-repeat;
}
.runtime-stage.show-grid {
  background-image: linear-gradient(
      rgba(255, 255, 255, 0.03) 1px,
      transparent 1px
    ),
    linear-gradient(90deg, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
  background-size: 32px 32px;
}
.runtime-watermark {
  position: absolute;
  inset: 0;
  pointer-events: none;
  display: flex;
  align-items: center;
  justify-content: center;
  transform: rotate(-18deg);
  opacity: 0.1;
  color: #d4e0ee;
  font-size: 28px;
  letter-spacing: 5px;
}
.runtime-widget {
  position: absolute;
  overflow: hidden;
  box-sizing: border-box;
  padding: 13px 14px;
  border: 1px solid rgba(121, 166, 197, 0.24);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.025);
  background: rgba(17, 26, 41, 0.84);
  transition:
    border-color 0.16s,
    box-shadow 0.16s;
}
.runtime-widget:hover,
.runtime-widget.active {
  border-color: rgba(53, 212, 176, 0.75);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.06),
    0 0 0 1px rgba(53, 212, 176, 0.15);
}
.runtime-widget.stale {
  border-color: rgba(222, 175, 77, 0.55);
}
.widget-heading {
  height: 25px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #9cabbc;
  font-size: 12px;
}
.quality-badge {
  font-size: 9px;
  line-height: 16px;
  padding: 0 5px;
  border-radius: 4px;
}
.quality-ok {
  color: #68d4b3;
  background: rgba(53, 212, 176, 0.12);
}
.quality-stale {
  color: #e5b35e;
  background: rgba(229, 179, 94, 0.12);
}
.quality-error {
  color: #ef9191;
  background: rgba(239, 145, 145, 0.12);
}
.metric-value {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-content-justify, center);
  gap: 8px;
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  text-align: var(--widget-text-align, center);
  letter-spacing: 1px;
}
.metric-value small {
  color: #95a5b6;
  font-size: 13px;
  font-weight: 450;
}
.flip-value {
  text-shadow: 0 0 16px color-mix(in srgb, var(--accent) 45%, transparent);
}
.number-flip-content.split-digits {
  gap: var(--flip-cell-gap, 8px);
}
.flip-cells {
  display: inline-flex;
  align-items: center;
  gap: var(--flip-cell-gap, 8px);
}
.flip-cells b {
  width: var(--flip-cell-width, 42px);
  height: var(--flip-cell-height, 58px);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border: 1px solid var(--flip-cell-border, var(--accent));
  border-radius: 4px;
  background: var(--flip-cell-background, rgba(28, 56, 86, 0.72));
  color: inherit;
  font: inherit;
  line-height: 1;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.12),
    0 0 12px color-mix(in srgb, var(--accent) 18%, transparent);
}
.chart-host {
  position: relative;
  width: 100%;
  height: calc(100% - 25px);
}
.chart-box {
  width: 100%;
  height: 100%;
}
.chart-empty-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8293a6;
  font-size: 12px;
  pointer-events: none;
  background: rgba(17, 26, 41, 0.28);
}
.text-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-content-justify, center);
  text-align: var(--widget-text-align, center);
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  white-space: pre-wrap;
}
.image-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  color: #7f94aa;
  gap: 7px;
  font-size: 11px;
}
.image-content .el-icon {
  font-size: 38px;
}
.image-content img {
  width: 100%;
  height: 100%;
  object-fit: var(--image-fit, contain);
  object-position: var(--image-position, center);
}
.decoration-content {
  position: absolute;
  inset: 50% 10%;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
}
.data-content {
  height: calc(100% - 25px);
  overflow: hidden;
}
.table-scroll-shell {
  width: 100%;
  height: 100%;
  overflow: hidden;
}
.runtime-table {
  width: 100%;
  border-collapse: collapse;
  color: var(--table-text-color, #cbd8e6);
  font-size: var(--widget-font-size, 11px);
}
.runtime-table th,
.runtime-table td {
  text-align: var(--widget-text-align, left);
  padding: 7px var(--table-cell-padding, 6px);
  border-bottom: 1px solid rgba(135, 161, 184, 0.12);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}
.runtime-table th {
  color: var(--table-header-color, #8497aa);
  background: var(--table-header-background, transparent);
  font-weight: 500;
}
.runtime-table tbody tr {
  cursor: pointer;
}
.runtime-table tbody tr:hover {
  background: rgba(53, 212, 176, 0.05);
}
.generic-widget {
  color: #9baabd;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.widget-state {
  position: absolute;
  inset: 25px 0 0;
}
.error-state {
  color: #ef9696;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  font-size: 13px;
}
.error-state .el-icon {
  font-size: 20px;
}
.error-state small {
  color: #95a6b8;
  font-size: 10px;
}
.empty-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #8293a6;
  font-size: 12px;
}
.stale-label,
.business-time {
  position: absolute;
  bottom: 5px;
  color: #8899aa;
  font-size: 9px;
}
.stale-label {
  left: 12px;
  color: #d6aa5c;
  display: flex;
  gap: 3px;
  align-items: center;
}
.business-time {
  right: 12px;
}
.stage-scale {
  position: fixed;
  bottom: 16px;
  right: 18px;
  color: #6f8298;
  background: rgba(18, 29, 44, 0.88);
  border: 1px solid #2b3d52;
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 10px;
}
.embed-mode .stage-scale {
  display: none;
}
.runtime-error {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.runtime-error :deep(.el-result__title) {
  color: #e5edf6;
}
.runtime-error :deep(.el-result__subtitle) {
  color: #96a9bb;
}
:global(html.dashboard-embed),
:global(body.dashboard-embed) {
  background: transparent !important;
  overflow: hidden;
}
:global(.dashboard-embed .sidebar-container),
:global(.dashboard-embed .fixed-header),
:global(.dashboard-embed .copyright) {
  display: none !important;
}
:global(.dashboard-embed .main-container) {
  margin-left: 0 !important;
}
:global(.dashboard-embed .app-main) {
  min-height: 100vh !important;
  height: 100vh !important;
  margin: 0 !important;
  padding: 0 !important;
}
.runtime-page {
  min-height: 100vh;
  height: 100vh;
  overflow: hidden;
  background: #0c1422;
  color: #e5edf6;
  display: flex;
  flex-direction: column;
}
.runtime-page.embed-mode {
  background: transparent;
}
.runtime-toolbar {
  height: 56px;
  flex: 0 0 56px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  background: #121c2b;
  border-bottom: 1px solid #253449;
}
.runtime-title,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.runtime-title {
  min-width: 0;
}
.runtime-title :deep(.el-button) {
  color: #9aabbd;
}
.toolbar-divider {
  height: 18px;
  width: 1px;
  background: #344358;
}
.page-name {
  font-size: 16px;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.toolbar-right {
  color: #8fa0b3;
  font-size: 11px;
}
.toolbar-right :deep(.el-button) {
  color: #9caec0;
}
.preview-note {
  color: #e8b660;
  display: inline-flex;
  gap: 5px;
  align-items: center;
}
.stage-viewport {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 22px;
  background: radial-gradient(
    circle at 50% 35%,
    rgba(30, 54, 81, 0.18),
    transparent 50%
  );
}
.stage-viewport.embed-viewport {
  padding: 0;
  align-items: flex-start;
  background: transparent;
}
.stage-holder {
  position: relative;
  flex: 0 0 auto;
}
.runtime-stage {
  position: relative;
  transform-origin: top left;
  overflow: hidden;
  box-shadow: 0 20px 70px rgba(0, 0, 0, 0.38);
  border-radius: 3px;
  background-repeat: no-repeat;
}
.runtime-stage.show-grid {
  background-image: linear-gradient(
      rgba(255, 255, 255, 0.03) 1px,
      transparent 1px
    ),
    linear-gradient(90deg, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
  background-size: 32px 32px;
}
.runtime-watermark {
  position: absolute;
  inset: 0;
  pointer-events: none;
  display: flex;
  align-items: center;
  justify-content: center;
  transform: rotate(-18deg);
  opacity: 0.1;
  color: #d4e0ee;
  font-size: 28px;
  letter-spacing: 5px;
}
.runtime-widget {
  position: absolute;
  overflow: hidden;
  box-sizing: border-box;
  padding: 13px 14px;
  border: 1px solid rgba(121, 166, 197, 0.24);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.025);
  background: rgba(17, 26, 41, 0.84);
  transition:
    border-color 0.16s,
    box-shadow 0.16s;
}
.runtime-widget:hover,
.runtime-widget.active {
  border-color: rgba(53, 212, 176, 0.75);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.06),
    0 0 0 1px rgba(53, 212, 176, 0.15);
}
.runtime-widget.stale {
  border-color: rgba(222, 175, 77, 0.55);
}
.widget-heading {
  height: 25px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #9cabbc;
  font-size: 12px;
}
.quality-badge {
  font-size: 9px;
  line-height: 16px;
  padding: 0 5px;
  border-radius: 4px;
}
.quality-ok {
  color: #68d4b3;
  background: rgba(53, 212, 176, 0.12);
}
.quality-stale {
  color: #e5b35e;
  background: rgba(229, 179, 94, 0.12);
}
.quality-error {
  color: #ef9191;
  background: rgba(239, 145, 145, 0.12);
}
.metric-value {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-content-justify, center);
  gap: 8px;
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  text-align: var(--widget-text-align, center);
  letter-spacing: 1px;
}
.metric-value small {
  color: #95a5b6;
  font-size: 13px;
  font-weight: 450;
}
.flip-value {
  text-shadow: 0 0 16px color-mix(in srgb, var(--accent) 45%, transparent);
}
.chart-box {
  width: 100%;
  height: calc(100% - 25px);
}
.text-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-content-justify, center);
  text-align: var(--widget-text-align, center);
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  white-space: pre-wrap;
}
.image-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  color: #7f94aa;
  gap: 7px;
  font-size: 11px;
}
.image-content .el-icon {
  font-size: 38px;
}
.image-content img {
  width: 100%;
  height: 100%;
  object-fit: var(--image-fit, contain);
  object-position: var(--image-position, center);
}
.decoration-content {
  position: absolute;
  inset: 50% 10%;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
}
.data-content {
  height: calc(100% - 25px);
  overflow: hidden;
}
.runtime-table {
  width: 100%;
  border-collapse: collapse;
  color: var(--table-text-color, #cbd8e6);
  font-size: var(--widget-font-size, 11px);
}
.runtime-table th,
.runtime-table td {
  text-align: var(--widget-text-align, left);
  padding: 7px var(--table-cell-padding, 6px);
  border-bottom: 1px solid rgba(135, 161, 184, 0.12);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}
.runtime-table th {
  color: var(--table-header-color, #8497aa);
  background: var(--table-header-background, transparent);
  font-weight: 500;
}
.runtime-table tbody tr:hover {
  background: rgba(53, 212, 176, 0.05);
}
.generic-widget {
  color: #9baabd;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.widget-state {
  position: absolute;
  inset: 25px 0 0;
}
.error-state {
  color: #ef9696;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  font-size: 13px;
}
.error-state .el-icon {
  font-size: 20px;
}
.error-state small {
  color: #95a6b8;
  font-size: 10px;
}
.empty-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #8293a6;
  font-size: 12px;
}
.stale-label,
.business-time {
  position: absolute;
  bottom: 5px;
  color: #8899aa;
  font-size: 9px;
}
.stale-label {
  left: 12px;
  color: #d6aa5c;
  display: flex;
  gap: 3px;
  align-items: center;
}
.business-time {
  right: 12px;
}
.stage-scale {
  position: fixed;
  bottom: 16px;
  right: 18px;
  color: #6f8298;
  background: rgba(18, 29, 44, 0.88);
  border: 1px solid #2b3d52;
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 10px;
}
.embed-mode .stage-scale {
  display: none;
}
.runtime-error {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.runtime-error :deep(.el-result__title) {
  color: #e5edf6;
}
.runtime-error :deep(.el-result__subtitle) {
  color: #96a9bb;
}
</style>

<style scoped>
.widget-heading b {
  font-weight: 600;
}
.widget-heading small {
  margin-left: 6px;
  color: #aebccc;
  font-size: 0.82em;
  font-weight: 400;
}
.runtime-widget.title-hidden .chart-host,
.runtime-widget.title-hidden .chart-box,
.runtime-widget.title-hidden .metric-value,
.runtime-widget.title-hidden .text-content,
.runtime-widget.title-hidden .image-content,
.runtime-widget.title-hidden .data-content,
.runtime-widget.title-hidden .word-cloud-box,
.runtime-widget.title-hidden .icon-content,
.runtime-widget.title-hidden .button-content,
.runtime-widget.title-hidden .tabs-content,
.runtime-widget.title-hidden .filter-form-content,
.runtime-widget.title-hidden .clock-content,
.runtime-widget.title-hidden .weather-content,
.runtime-widget.title-hidden .color-block-content,
.runtime-widget.title-hidden .video-content,
.runtime-widget.title-hidden .iframe-content,
.runtime-widget.title-hidden .custom-html-content,
.runtime-widget.title-hidden .map-content,
.runtime-widget.title-hidden .statistics-content,
.runtime-widget.title-hidden .carousel-content,
.runtime-widget.title-hidden .advanced-table-wrap,
.runtime-widget.title-hidden .access-list-content,
.runtime-widget.title-hidden .ring-text-content,
.runtime-widget.title-hidden > .dashboard-gantt {
  height: 100%;
}
.runtime-widget.title-hidden .widget-state {
  inset: 0;
}
.runtime-table th.sortable {
  cursor: pointer;
  user-select: none;
}
.runtime-table th.sortable:hover {
  color: #b9d7d2;
}
.sort-mark {
  margin-left: 4px;
  color: var(--accent);
}
.statistics-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: space-between;
  gap: 12px;
  text-align: var(--widget-text-align, left);
}
.statistics-main {
  min-width: 0;
}
.stats-layout-horizontal .statistics-main {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.stats-layout-vertical .statistics-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: var(--widget-content-justify, flex-start);
  justify-content: center;
}
.stats-label-after .statistics-label {
  order: 2;
  margin-top: 4px;
  margin-bottom: 0;
}
.stats-label-after .statistics-main strong {
  order: 1;
}
.statistics-label {
  display: block;
  color: var(--stats-label-color, #9aabbd);
  font-size: var(--stats-label-size, 11px);
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.statistics-main strong {
  display: flex;
  align-items: baseline;
  gap: 4px;
  color: var(--accent);
  font-weight: var(--widget-font-weight, 400);
  line-height: 1.1;
}
.statistics-main strong small {
  color: var(--stats-unit-color, #9aabbd);
  font-size: var(--stats-unit-size, 12px);
  font-weight: 500;
}
.statistics-compare {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  color: #8fa0b3;
  font-size: 10px;
  white-space: nowrap;
}
.statistics-compare b {
  font-size: 13px;
}
.compare-up b {
  color: var(--stats-up, #35d4b0);
}
.compare-down b {
  color: var(--stats-down, #ef8d8d);
}
.stats-compact {
  align-items: center;
}
.stats-compact .statistics-label {
  margin-bottom: 3px;
}
.carousel-content {
  height: calc(100% - 25px);
}

.advanced-table-wrap {
  height: 100%;
  overflow: hidden;
}
.advanced-table-wrap .runtime-table {
  table-layout: fixed;
}
.advanced-table-wrap .runtime-table th,
.advanced-table-wrap .runtime-table td {
  height: var(--advanced-row-height);
  box-sizing: border-box;
  padding-top: 0;
  padding-bottom: 0;
}
.advanced-table-wrap .runtime-table .table-primary-column {
  width: var(--table-first-column-width, 50%);
}
.advanced-table-wrap .runtime-table tbody tr:nth-child(even) {
  background: transparent;
}
.advanced-table-wrap.stripe .runtime-table tbody tr:nth-child(even) {
  background: rgba(93, 128, 153, 0.1);
}
.advanced-table-wrap .runtime-table td:last-child {
  color: var(--table-value-color, var(--table-text-color, #cbd8e6));
}
.access-list-content {
  height: calc(100% - 25px);
  min-height: 0;
  overflow: hidden;
  text-align: left;
}
.access-list-row {
  position: relative;
  height: var(--access-row-height, 72px);
  min-height: var(--access-row-height, 72px);
  display: flex;
  align-items: center;
  box-sizing: border-box;
  gap: 12px;
  padding: 6px 2px;
  border-bottom: 1px solid rgba(45, 114, 170, 0.25);
  background: linear-gradient(90deg, rgba(7, 43, 76, 0.24), transparent);
  cursor: pointer;
}
.access-avatar {
  width: var(--access-avatar-size, 56px);
  height: var(--access-avatar-size, 56px);
  flex: 0 0 var(--access-avatar-size, 56px);
  box-sizing: border-box;
  border: 2px solid rgba(237, 253, 255, 0.92);
  border-radius: 50%;
  object-fit: cover;
  background: rgba(19, 54, 82, 0.88);
  box-shadow: 0 0 8px rgba(37, 171, 246, 0.38);
}
.access-vehicle .access-avatar {
  padding: 3px;
  object-fit: contain;
  background: rgba(12, 45, 72, 0.94);
  box-shadow:
    inset 0 0 12px rgba(86, 199, 255, 0.18),
    0 0 8px rgba(37, 171, 246, 0.38);
}
.access-person .access-avatar {
  object-fit: cover;
}
.access-copy {
  min-width: 0;
  flex: 1;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-right: 48px;
}
.access-primary {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 12px;
  white-space: nowrap;
}
.access-primary strong {
  flex: 0 0 auto;
  color: #f3f8fb;
  font-size: 18px;
  font-weight: 500;
}
.access-primary span {
  min-width: 0;
  color: #d2dde6;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.access-primary time {
  margin-left: auto;
  color: #c2d1dc;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}
.access-company {
  min-width: 0;
  margin-top: 5px;
  color: #eef6fb;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.access-status {
  position: absolute;
  right: 0;
  bottom: 8px;
  min-width: 50px;
  height: 24px;
  padding: 0 8px;
  box-sizing: border-box;
  border-radius: 5px;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  line-height: 24px;
  text-align: center;
}
.access-status.success {
  background: #00d887;
}
.access-status.neutral {
  background: #b8c0c7;
  color: #59636b;
}
.ring-text-content {
  height: calc(100% - 25px);
}
.map-content {
  isolation: isolate;
}
.map-content .map-chart-box {
  min-height: 0;
}
.drilldown-back-global {
  position: fixed;
  right: 18px;
  bottom: 48px;
  z-index: 20;
  padding: 6px 10px;
  border: 1px solid #38526c;
  border-radius: 4px;
  background: rgba(18, 29, 44, 0.92);
  color: #a9bed2;
  font-size: 10px;
  cursor: pointer;
}
.drilldown-back-global:hover {
  color: #d8f4ec;
  border-color: #35d4b0;
}
.dashboard-detail-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
}
.dashboard-detail-dialog :deep(.el-table) {
  --el-table-border-color: #e4eaf1;
  --el-table-header-bg-color: #f7f9fb;
}
</style>

<style scoped>
.runtime-filterbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 20px;
  background: #101a29;
  border-bottom: 1px solid #253449;
}
.runtime-filterbar label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #9aaabc;
  font-size: 11px;
}
.runtime-filterbar input {
  width: 150px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid #33445b;
  border-radius: 4px;
  outline: none;
  background: #172437;
  color: #e5edf6;
  font-size: 11px;
}
.runtime-filterbar input:focus {
  border-color: #35d4b0;
}
.embed-mode .runtime-filterbar {
  display: none;
}
.clock-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-content-justify, center);
  gap: 10px;
  color: var(--accent);
}
.clock-content .el-icon {
  font-size: clamp(22px, 3vw, 44px);
}
.clock-content strong {
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  letter-spacing: 0.06em;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.weather-content {
  height: calc(100% - 25px);
  display: grid;
  grid-template-columns: auto 1fr;
  grid-template-rows: 1fr auto;
  align-items: center;
  gap: 5px 12px;
  color: #dce8f5;
}
.weather-symbol {
  font-size: clamp(30px, 4vw, 62px);
  color: var(--accent);
}
.weather-content strong {
  display: block;
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
}
.weather-content span {
  display: block;
  margin-top: 3px;
  color: #9aabbd;
  font-size: 12px;
}
.weather-content > small {
  grid-column: 1 / -1;
  color: #8fa0b3;
  font-size: 10px;
}
.color-block-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-content-justify, center);
  gap: 12px;
  color: #dce8f5;
}
.color-block-content strong {
  font-weight: var(--widget-font-weight, 400);
}
.color-block-swatch {
  width: clamp(42px, 5vw, 86px);
  height: clamp(42px, 5vw, 86px);
  border-radius: 12px;
  box-shadow: 0 0 20px color-mix(in srgb, var(--accent) 42%, transparent);
}
.video-content,
.iframe-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.video-content video,
.iframe-content iframe {
  width: 100%;
  height: 100%;
  border: 0;
  object-fit: contain;
  background: rgba(0, 0, 0, 0.25);
}
.custom-html-content {
  position: relative;
  height: calc(100% - 25px);
  min-height: 0;
  overflow: hidden;
  background: transparent;
}
.custom-html-content iframe {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: transparent;
}
.custom-loading-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(7, 18, 29, 0.52);
  color: #b8c7d4;
  font-size: 11px;
  pointer-events: none;
}
.media-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: #8194a9;
  font-size: 11px;
}
.media-placeholder .el-icon {
  font-size: 34px;
  color: var(--accent);
}
.map-content {
  height: calc(100% - 25px);
  position: relative;
  overflow: hidden;
}
.map-render-wrap,
.map-chart-box {
  width: 100%;
  height: 100%;
}
.map-render-wrap {
  position: relative;
}
.map-status {
  position: absolute;
  left: 50%;
  bottom: 8px;
  max-width: calc(100% - 16px);
  transform: translateX(-50%);
  padding: 4px 7px;
  border-radius: 4px;
  color: #b8c7d4;
  background: rgba(7, 18, 29, 0.82);
  font-size: 9px;
  text-align: center;
  pointer-events: none;
}
.map-grid {
  position: absolute;
  inset: 4px;
  border: 1px solid rgba(113, 157, 187, 0.25);
  border-radius: 50% 45% 48% 42%;
  background: radial-gradient(
    ellipse at center,
    rgba(48, 116, 150, 0.28),
    rgba(13, 34, 55, 0.72) 68%,
    rgba(8, 19, 32, 0.9)
  );
  overflow: hidden;
}
.map-grid::before,
.map-grid::after {
  content: "";
  position: absolute;
  inset: 8% 16%;
  border: 1px dashed rgba(110, 189, 206, 0.2);
  border-radius: 45% 55% 46% 54%;
  transform: rotate(-12deg);
}
.map-grid::after {
  inset: 20% 5%;
  transform: rotate(22deg);
}
.map-point {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 4px;
  transform: translate(-50%, -50%);
  color: #dbeaf4;
  font-size: 9px;
  cursor: pointer;
  white-space: nowrap;
}
.map-point i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--point-color);
  box-shadow:
    0 0 0 4px color-mix(in srgb, var(--point-color) 22%, transparent),
    0 0 14px var(--point-color);
}
.map-point b {
  font-weight: 500;
  text-shadow: 0 1px 3px #05111e;
}
.map-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8194a9;
  font-size: 10px;
}
.icon-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-content-justify, center);
  color: var(--accent);
  font-size: var(--widget-font-size, 32px);
}
.word-cloud-box {
  height: calc(100% - 25px);
  display: flex;
  align-content: center;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  padding: 8px 14px;
  overflow: hidden;
}
.word-cloud-box button {
  border: 0;
  padding: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  line-height: 1.2;
  transition:
    opacity 0.15s,
    transform 0.15s;
}
.word-cloud-box button:hover {
  opacity: 0.78;
  transform: scale(1.06);
}
.button-content {
  height: calc(100% - 25px);
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-content-justify, center);
}
.button-content button {
  border: var(--widget-border-width, 1px)
    var(--widget-border-style, solid)
    var(
      --widget-border-color,
      color-mix(in srgb, var(--accent) 70%, transparent)
    );
  border-radius: 6px;
  padding: var(--button-padding-y, 8px) var(--button-padding-x, 22px);
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
  cursor: pointer;
  font-size: var(--widget-font-size, 13px);
  font-weight: var(--widget-font-weight, 400);
  text-align: var(--widget-text-align, center);
}
.button-content button:hover {
  background: color-mix(in srgb, var(--accent) 28%, transparent);
}
.tabs-content,
.filter-form-content {
  height: calc(100% - 25px);
}
.runtime-page.theme-light {
  background: #f2f5f8;
  color: #253144;
}
.runtime-page.theme-light .runtime-toolbar {
  background: rgba(255, 255, 255, 0.95);
  border-bottom-color: #dfe6ee;
}
.runtime-page.theme-light .runtime-title :deep(.el-button),
.runtime-page.theme-light .toolbar-right,
.runtime-page.theme-light .toolbar-right :deep(.el-button) {
  color: #647286;
}
.runtime-page.theme-light .stage-viewport {
  background: #eef2f6;
}
.runtime-page.theme-light .runtime-widget {
  border-color: rgba(111, 135, 158, 0.28);
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 6px 18px rgba(43, 62, 82, 0.08);
}
.runtime-page.theme-light .widget-heading {
  color: #596a7f;
}
.runtime-page.theme-light .runtime-table {
  color: #3b4b5f;
}
.runtime-page.theme-light .runtime-table th {
  color: #718198;
}
.runtime-page.theme-light .runtime-table th,
.runtime-page.theme-light .runtime-table td {
  border-bottom-color: rgba(111, 135, 158, 0.18);
}
.runtime-page.theme-light .tabs-nav {
  border-bottom-color: rgba(111, 135, 158, 0.2);
}
.runtime-page.theme-light .tabs-nav button {
  color: #718198;
}
.runtime-page.theme-light .tabs-body,
.runtime-page.theme-light .text-content {
  color: #3d4c60;
}
.runtime-page.theme-light .filter-form-content input,
.runtime-page.theme-light .filter-form-content select {
  background: #fff;
  color: #3d4c60;
  border-color: #cbd6e2;
}
.runtime-page.theme-light .stage-scale {
  color: #5d6d81;
  background: rgba(255, 255, 255, 0.92);
  border-color: #d5dee8;
}
.runtime-widget .widget-heading {
  height: var(--widget-title-height, 25px);
}
.runtime-widget.title-image .widget-heading {
  box-sizing: border-box;
}
.runtime-widget.title-hidden > .milestone-timeline-content {
  height: 100%;
}
.runtime-widget:not(.title-hidden) > .milestone-timeline-content {
  height: calc(100% - var(--widget-title-height, 25px));
}
.milestone-timeline-content {
  position: relative;
  display: flex;
  align-items: center;
  padding: 16px 10px 6px;
  box-sizing: border-box;
  overflow: hidden;
}
.milestone-timeline-content::before {
  content: "";
  position: absolute;
  left: 6%;
  right: 6%;
  top: calc(50% - 18px);
  height: 3px;
  border-radius: 3px;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--timeline-done, var(--accent)) 85%, transparent),
    rgba(75, 126, 174, 0.38)
  );
  box-shadow: 0 0 8px
    color-mix(in srgb, var(--timeline-done, var(--accent)) 28%, transparent);
}
.milestone-node {
  position: relative;
  z-index: 1;
  flex: 1 1 0;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  color: #8294a8;
  cursor: pointer;
}
.milestone-node i {
  width: 16px;
  height: 16px;
  box-sizing: border-box;
  border: 2px solid #3d6990;
  background: #11253a;
  transform: rotate(45deg);
  box-shadow: 0 0 0 4px rgba(42, 105, 165, 0.14);
}
.milestone-node.done i {
  border-color: var(--timeline-done, var(--accent));
}
.milestone-node.active i {
  border-color: var(--timeline-active, var(--accent));
}
.milestone-node.active i {
  background: var(--timeline-active, var(--accent));
  box-shadow:
    0 0 14px var(--timeline-active, var(--accent)),
    0 0 0 5px
      color-mix(
        in srgb,
        var(--timeline-active, var(--accent)) 18%,
        transparent
      );
}
.milestone-node b {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #d3dfeb;
  font-size: 12px;
  font-weight: 500;
}
.milestone-node.active b {
  color: var(--timeline-active, var(--accent));
}
.milestone-node span,
.milestone-node small {
  color: #a8b7c6;
  font-size: 10px;
  white-space: nowrap;
}
.milestone-node small {
  color: #718397;
}
.milestone-timeline-content.labels-above .milestone-node b {
  order: 1;
  margin-bottom: 7px;
}
.milestone-timeline-content.labels-above .milestone-node i {
  order: 2;
  margin-bottom: 10px;
}
.milestone-timeline-content.labels-above .milestone-node span {
  order: 3;
}
.milestone-timeline-content.labels-above .milestone-node small {
  order: 4;
  margin-top: 3px;
}
.milestone-timeline-content.labels-above.layout-spread {
  padding-top: 0;
  padding-bottom: 0;
}
.milestone-timeline-content.labels-above.layout-spread::before {
  top: 47%;
}
.milestone-timeline-content.labels-above.layout-spread .milestone-node {
  position: relative;
  justify-content: flex-start;
  gap: 0;
}
.milestone-timeline-content.labels-above.layout-spread .milestone-node b,
.milestone-timeline-content.labels-above.layout-spread .milestone-node i,
.milestone-timeline-content.labels-above.layout-spread .milestone-node span,
.milestone-timeline-content.labels-above.layout-spread .milestone-node small {
  position: absolute;
  margin: 0;
}
.milestone-timeline-content.labels-above.layout-spread .milestone-node b {
  top: 23%;
}
.milestone-timeline-content.labels-above.layout-spread .milestone-node i {
  top: calc(47% - 7px);
}
.milestone-timeline-content.labels-above.layout-spread .milestone-node span {
  top: 66%;
}
.milestone-timeline-content.labels-above.layout-spread .milestone-node small {
  top: 80%;
}
.runtime-widget:not(.title-hidden) > .chart-host,
.runtime-widget:not(.title-hidden) > .metric-value,
.runtime-widget:not(.title-hidden) > .text-content,
.runtime-widget:not(.title-hidden) > .image-content,
.runtime-widget:not(.title-hidden) > .data-content,
.runtime-widget:not(.title-hidden) > .word-cloud-box,
.runtime-widget:not(.title-hidden) > .icon-content,
.runtime-widget:not(.title-hidden) > .button-content,
.runtime-widget:not(.title-hidden) > .tabs-content,
.runtime-widget:not(.title-hidden) > .filter-form-content,
.runtime-widget:not(.title-hidden) > .clock-content,
.runtime-widget:not(.title-hidden) > .weather-content,
.runtime-widget:not(.title-hidden) > .color-block-content,
.runtime-widget:not(.title-hidden) > .video-content,
.runtime-widget:not(.title-hidden) > .iframe-content,
.runtime-widget:not(.title-hidden) > .custom-html-content,
.runtime-widget:not(.title-hidden) > .map-content,
.runtime-widget:not(.title-hidden) > .statistics-content,
.runtime-widget:not(.title-hidden) > .carousel-content,
.runtime-widget:not(.title-hidden) > .advanced-table-wrap,
.runtime-widget:not(.title-hidden) > .access-list-content,
.runtime-widget:not(.title-hidden) > .ring-text-content,
.runtime-widget:not(.title-hidden) > .dashboard-gantt {
  height: calc(100% - var(--widget-title-height, 25px));
}
.runtime-widget:not(.title-hidden) > .widget-state {
  inset: var(--widget-title-height, 25px) 0 0;
}
.runtime-widget.border-transparent,
.runtime-widget.border-transparent:hover,
.runtime-widget.border-transparent.active,
.runtime-widget.border-transparent.stale,
.runtime-page.theme-light .runtime-widget.border-transparent {
  border-color: transparent !important;
}
.runtime-widget.widget-embedded,
.runtime-widget.widget-embedded:hover,
.runtime-widget.widget-embedded.active,
.runtime-page.theme-light .runtime-widget.widget-embedded {
  border-color: transparent;
  box-shadow: none;
  padding: 0;
}
.runtime-widget.widget-embedded .carousel-card,
.runtime-widget.widget-embedded
  .advanced-table-wrap.stripe
  .runtime-table
  tbody
  tr:nth-child(even),
.runtime-widget.widget-embedded .access-list-row,
.runtime-widget.widget-embedded .chart-empty-overlay,
.runtime-widget.widget-embedded .custom-loading-mask,
.runtime-widget.widget-embedded .map-status {
  background: transparent;
  box-shadow: none;
}
.runtime-widget.widget-embedded .carousel-card {
  border-color: transparent;
  padding-left: 0;
  padding-right: 0;
}
.runtime-widget.widget-embedded .button-content button,
.runtime-widget.widget-embedded .filter-form-content input,
.runtime-widget.widget-embedded .filter-form-content select,
.runtime-widget.widget-embedded .filter-form-content > button {
  background: transparent;
  box-shadow: none;
}
.chart-box {
  width: 100%;
  height: 100%;
}
.runtime-page {
  position: relative;
}
.runtime-page:fullscreen,
.runtime-page.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 3000;
  width: 100vw;
  height: 100vh;
  min-height: 100vh;
}
.runtime-page.is-fullscreen .runtime-toolbar,
.runtime-page.is-fullscreen .runtime-filterbar {
  display: none;
}
.runtime-page.is-fullscreen .stage-viewport {
  padding: 0;
  align-items: flex-start;
  justify-content: flex-start;
  overflow: auto;
  background: #000;
}
.runtime-page.is-fullscreen .stage-holder {
  margin: auto;
  overflow: hidden;
}
.runtime-page.is-fullscreen .runtime-stage {
  border-radius: 0;
  box-shadow: none;
}
.runtime-page.is-fullscreen .stage-scale {
  display: none;
}
.runtime-page.is-fullscreen .fullscreen-hover-zone {
  position: fixed;
  left: auto !important;
  right: 12px;
  top: 12px !important;
}
.fullscreen-hover-zone {
  position: absolute;
  z-index: 50;
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  pointer-events: auto;
  transition: opacity 0.16s ease;
}
.fullscreen-hover-zone:hover,
.fullscreen-hover-zone:focus-within {
  opacity: 1;
}
.fullscreen-enter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid rgba(120, 164, 196, 0.45);
  border-radius: 5px;
  background: rgba(8, 22, 38, 0.72);
  color: #b9cce0;
  cursor: pointer;
}
.fullscreen-enter:hover,
.fullscreen-enter:focus-visible {
  border-color: var(--accent, #35d4b0);
  color: var(--accent, #35d4b0);
  outline: none;
}
</style>

<style scoped>
.widget-heading { box-sizing: border-box; line-height: 1.25; }
.widget-heading-copy { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.widget-heading-copy small { margin-left: 0; }
.stats-compact { gap: 6px; }
.runtime-refresh-notice { position: absolute; z-index: 60; top: 8px; right: 56px; padding: 4px 8px; border-radius: 4px; background: var(--el-color-warning-light-9); color: var(--el-color-warning); font-size: 12px; pointer-events: none; }
</style>
