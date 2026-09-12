<template>
  <div
    class="designer-shell"
    :class="{ 'is-dark': schema.canvas.theme === 'dark' }"
    @pointerdown="closeContextMenu"
  >
    <header class="designer-header">
      <div class="header-left">
        <el-button class="back-button" link @click="back"
          ><el-icon><Back /></el-icon>页面</el-button
        >
        <span class="header-divider"></span>
        <div class="page-title-wrap" @dblclick="beginPageNameEdit">
          <el-input
            v-if="pageNameEditing"
            ref="pageNameInput"
            v-model="pageNameDraft"
            class="page-name-editor"
            maxlength="100"
            show-word-limit
            @keyup.enter="commitPageName"
            @keyup.esc="cancelPageNameEdit"
            @blur="commitPageName"
          />
          <template v-else>
            <span class="page-title">{{ page.pageName || "未命名大屏" }}</span>
            <span class="page-code">/{{ page.pageCode || "草稿" }}</span>
          </template>
        </div>
        <el-tooltip v-if="!pageNameEditing" content="编辑页面名称"
          ><el-button
            text
            circle
            class="edit-page-name"
            @click="beginPageNameEdit"
            ><el-icon><Edit /></el-icon></el-button
        ></el-tooltip>
        <el-tag
          v-if="draftDirty"
          class="dirty-tag"
          type="warning"
          effect="plain"
          >未保存</el-tag
        >
        <el-tag v-else class="saved-tag" type="success" effect="plain"
          >已保存</el-tag
        >
      </div>
      <div class="header-center">
        <el-tooltip content="撤销 (⌘/Ctrl + Z)"
          ><el-button text :disabled="!history.length" @click="undo"
            ><el-icon><RefreshLeft /></el-icon></el-button
        ></el-tooltip>
        <el-tooltip content="重做 (⌘/Ctrl + Shift + Z)"
          ><el-button text :disabled="!future.length" @click="redo"
            ><el-icon><RefreshRight /></el-icon></el-button
        ></el-tooltip>
        <span class="header-divider small"></span>
        <el-tooltip content="复制选中组件"
          ><el-button text :disabled="!selectedIds.length" @click="copyWidgets"
            ><el-icon><CopyDocument /></el-icon></el-button
        ></el-tooltip>
        <el-tooltip content="删除选中组件"
          ><el-button
            text
            :disabled="!selectedIds.length"
            @click="deleteSelected"
            ><el-icon><Delete /></el-icon></el-button
        ></el-tooltip>
      </div>
      <div class="header-actions">
        <div class="data-entry-actions">
          <el-button
            v-hasPermi="['dashboard:dataset:list']"
            text
            class="data-manage-button"
            @click="sourceManagement"
            ><el-icon><Link /></el-icon>数据源</el-button
          ><el-button
            v-hasPermi="['dashboard:dataset:list']"
            text
            class="data-manage-button"
            @click="datasetManagement"
            ><el-icon><DataBoard /></el-icon>数据集</el-button
          >
        </div>
        <div class="preview-actions">
          <el-button
            v-hasPermi="['dashboard:page:preview']"
            plain
            @click="preview('current')"
            :loading="previewing"
            ><el-icon><VideoPlay /></el-icon>预览</el-button
          >
          <el-dropdown
            v-hasPermi="['dashboard:page:preview']"
            trigger="click"
            @command="previewCommand"
          >
            <el-button plain class="preview-options" :disabled="previewing"
              ><el-icon><ArrowDown /></el-icon
            ></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="current"
                  ><el-icon><Monitor /></el-icon>在本窗口预览</el-dropdown-item
                >
                <el-dropdown-item command="new"
                  ><el-icon><CopyDocument /></el-icon
                  >在新窗口预览</el-dropdown-item
                >
                <el-dropdown-item command="fullscreen"
                  ><el-icon><FullScreen /></el-icon
                  >全屏适配预览</el-dropdown-item
                >
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <el-button @click="save" :loading="saving"
          ><el-icon><DocumentChecked /></el-icon>保存草稿</el-button
        >
        <el-button
          v-hasPermi="['dashboard:page:publish']"
          type="primary"
          @click="publish"
          :loading="publishing"
          ><el-icon><Promotion /></el-icon>发布</el-button
        >
        <el-button v-if="canRunPage" type="success" plain @click="runtime" :loading="checkingRuntime"
          ><el-icon><Monitor /></el-icon>运行</el-button
        >
        <el-dropdown trigger="click" @command="handleMore">
          <el-button text class="more-button"
            ><el-icon><MoreFilled /></el-icon
          ></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="canCopyPage" command="copy-page"
                ><el-icon><DocumentCopy /></el-icon>复制页面</el-dropdown-item
              >
              <el-dropdown-item command="page-settings"
                ><el-icon><Setting /></el-icon>页面设置</el-dropdown-item
              >
              <el-dropdown-item command="export-json"
                ><el-icon><Download /></el-icon>导出页面 JSON</el-dropdown-item
              >
              <el-dropdown-item command="import-json"
                ><el-icon><Upload /></el-icon>导入页面 JSON</el-dropdown-item
              >
              <el-dropdown-item command="shortcuts"
                ><el-icon><InfoFilled /></el-icon>快捷键</el-dropdown-item
              >
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div v-loading="loading" class="designer-body">
      <aside class="left-panel">
        <div class="panel-tabs">
          <button
            :class="{ active: leftTab === 'components' }"
            @click="leftTab = 'components'"
          >
            <el-icon><Grid /></el-icon>组件
          </button>
          <button
            :class="{ active: leftTab === 'layers' }"
            @click="leftTab = 'layers'"
          >
            <el-icon><Sort /></el-icon>图层
          </button>
        </div>

        <div v-if="leftTab === 'components'" class="components-panel">
          <div class="panel-intro">
            <div class="panel-heading">添加组件</div>
            <span>点击组件添加到画布</span>
          </div>
          <section
            v-for="group in componentGroups"
            :key="group.key"
            class="component-group"
          >
            <div class="group-heading">
              <el-icon><component :is="group.icon" /></el-icon>{{ group.label
              }}<span>{{ group.items.length }}</span>
            </div>
            <div class="component-grid">
              <button
                v-for="item in group.items"
                :key="item.type"
                class="component-item"
                :title="item.description"
                draggable="true"
                @dragstart="startPaletteDrag($event, item)"
                @click="addWidget(item)"
              >
                <span class="component-icon" :class="`icon-${item.tone}`"
                  ><el-icon><component :is="item.icon" /></el-icon
                ></span>
                <span class="component-label">{{ item.label }}</span>
              </button>
            </div>
          </section>
          <div class="palette-tip">
            <el-icon><MagicStick /></el-icon
            ><span>组件通过数据集取数，运行态不会直连原始系统。</span>
          </div>
        </div>

        <div v-else class="layers-panel">
          <div class="layers-toolbar">
            <span class="panel-heading">页面图层</span>
            <span class="layer-count">{{ schema.widgets.length }} 个组件</span>
          </div>
          <el-input
            v-model="layerKeyword"
            class="layer-search"
            clearable
            placeholder="搜索图层"
          />
          <DesignerLayerTree
            :widgets="schema.widgets"
            :groups="schema.groups"
            :selected-ids="selectedIds"
            :member-edit-id="memberEditId"
            :keyword="layerKeyword"
            :drag-id="layerDragId"
            :meta="meta"
            @select-widget="selectLayerWidget"
            @select-group="selectLayerGroup"
            @rename-group="renameLayerGroup"
            @toggle-group="toggleLayerGroup"
            @toggle-visible="toggleVisible"
            @toggle-locked="toggleLocked"
            @drag-start="startLayerDrag"
            @drag-end="finishLayerDrag"
            @drop="dropLayer"
          />
        </div>
      </aside>

      <section class="workspace">
        <div class="workspace-toolbar">
          <div class="tool-group">
            <el-tooltip content="多选模式：逐个点击加入或取消，也可按住 Shift / Ctrl / ⌘ 多选">
              <el-button text :class="{ toggled: multiSelectMode }" aria-label="多选" :aria-pressed="multiSelectMode" @click="multiSelectMode = !multiSelectMode"><el-icon><DesignerLayoutIcon name="select" /></el-icon></el-button>
            </el-tooltip>
            <el-tooltip content="拖动画布（按住空格键可临时启用）"
              ><el-button
                text
                :class="{ toggled: panMode }"
                @click="panMode = !panMode"
                ><el-icon><Pointer /></el-icon></el-button
            ></el-tooltip>
            <el-tooltip content="显示/隐藏网格"
              ><el-button
                text
                :class="{ toggled: showGrid }"
                @click="showGrid = !showGrid"
                ><el-icon><Grid /></el-icon></el-button
            ></el-tooltip>
            <el-tooltip content="吸附网格"
              ><el-button
                text
                :class="{ toggled: snapToGrid }"
                @click="snapToGrid = !snapToGrid"
                ><el-icon><Connection /></el-icon></el-button
            ></el-tooltip>
            <span class="toolbar-divider"></span>
            <el-tooltip content="组合 (⌘/Ctrl + G)：将选中组件作为整体操作">
              <el-button text :disabled="!canGroupSelection()" aria-label="组合" @click="groupSelected"><el-icon><DesignerLayoutIcon name="group" /></el-icon></el-button>
            </el-tooltip>
            <el-tooltip content="取消组合 (⌘/Ctrl + Shift + G)">
              <el-button text :disabled="!canUngroupSelection()" aria-label="取消组合" @click="ungroupSelected"><el-icon><DesignerLayoutIcon name="ungroup" /></el-icon></el-button>
            </el-tooltip>
            <span class="toolbar-divider"></span>
            <el-tooltip content="置顶"
              ><el-button
                text
                :disabled="!selectedIds.length"
                @click="moveLayer('top')"
                ><el-icon><Top /></el-icon></el-button
            ></el-tooltip>
            <el-tooltip content="置底"
              ><el-button
                text
                :disabled="!selectedIds.length"
                @click="moveLayer('bottom')"
                ><el-icon><Bottom /></el-icon></el-button
            ></el-tooltip>
            <el-tooltip content="上移一层"
              ><el-button
                text
                :disabled="!selectedIds.length"
                @click="moveLayer('up')"
                ><el-icon><ArrowUp /></el-icon></el-button
            ></el-tooltip>
            <el-tooltip content="下移一层"
              ><el-button
                text
                :disabled="!selectedIds.length"
                @click="moveLayer('down')"
                ><el-icon><ArrowDown /></el-icon></el-button
            ></el-tooltip>
            <span class="arrangement-trigger" :title="!canArrangeSelection('left') ? (selectedHasLocked ? '选中项包含锁定组件，请先解锁后排列' : '对齐至少选择 2 个对象，组合按整体计算') : '对齐与分布'">
                <el-dropdown trigger="click" @command="alignSelected">
                  <el-button text :disabled="!canArrangeSelection('left')" aria-label="对齐与分布">
                    <el-icon><DesignerLayoutIcon name="left" /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        v-for="item in alignmentActions"
                        :key="item.command"
                        :command="item.command"
                        :divided="item.command === 'distribute-x'"
                        :disabled="!canArrangeSelection(item.command)"
                        :title="!canArrangeSelection(item.command) ? '平均分布至少选择 3 个对象，组合按整体计算' : ''"
                      >
                        <el-icon :size="16"><DesignerLayoutIcon :name="item.command" /></el-icon>{{ item.label }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
            </span>
          </div>
          <div class="canvas-info">
            <span>{{ schema.canvas.width }} × {{ schema.canvas.height }}</span
            ><span class="toolbar-divider"></span
            ><span>{{
              selectedIds.length ? `已选 ${selectedIds.length}` : "未选择组件"
            }}</span>
          </div>
          <div class="zoom-tools">
            <el-tooltip content="缩小"
              ><el-button text @click="changeZoom(-0.1)"
                ><el-icon><ZoomOut /></el-icon></el-button
            ></el-tooltip>
            <el-dropdown trigger="click" @command="setZoom">
              <button
                type="button"
                class="zoom-value"
                :aria-label="`当前显示比例 ${Math.round(canvasScale * 100)}%，点击选择显示比例`"
              >
                <span>{{ Math.round(canvasScale * 100) }}%</span
                ><el-icon><ArrowDown /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="fit"
                    ><el-icon><FullScreen /></el-icon>适应画布</el-dropdown-item
                  >
                  <el-dropdown-item
                    v-for="option in zoomOptions"
                    :key="option"
                    :command="option"
                    :divided="option === zoomOptions[0]"
                    >{{ option }}%</el-dropdown-item
                  >
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-tooltip content="放大"
              ><el-button text @click="changeZoom(0.1)"
                ><el-icon><ZoomIn /></el-icon></el-button
            ></el-tooltip>
            <el-tooltip content="适应画布"
              ><el-button text @click="fitCanvas"
                ><el-icon><FullScreen /></el-icon></el-button
            ></el-tooltip>
          </div>
        </div>

        <div
          ref="canvasViewport"
          class="canvas-viewport"
          tabindex="-1"
          :class="{ 'pan-ready': canvasPanEnabled, panning: canvasPan.active }"
          @pointerdown="handleCanvasPointerDown"
          @dragover.prevent
          @drop.prevent="dropPalette"
        >
          <div class="canvas-holder" :style="holderStyle">
            <div
              ref="canvasElement"
              class="canvas-surface"
              :class="{ 'show-grid': showGrid }"
              :style="canvasStyle"
            >
              <div
                v-if="
                  schema.canvas.watermark?.enabled &&
                  schema.canvas.watermark?.text
                "
                class="canvas-watermark"
                :style="canvasWatermarkStyle"
              >
                {{ schema.canvas.watermark.text }}
              </div>
              <div
                v-for="widget in orderedWidgets"
                :key="widget.id"
                class="canvas-widget"
                :class="{
                  selected: selectedIds.includes(widget.id),
                  primary: selectedId === widget.id,
                  locked: widget.state?.locked,
                  grouped: widget.groupId,
                  'background-transparent':
                    widget.style?.backgroundTransparent === true,
                  'border-transparent':
                    widget.style?.borderTransparent === true,
                  'widget-embedded': widget.style?.embeddedMode === true,
                  'title-image': hasTitleImage(widget),
                  'title-hidden': !isWidgetTitleVisible(widget),
                }"
                :style="widgetStyle(widget)"
                @pointerdown.stop="handleWidgetPointerDown($event, widget)"
                @click.stop
                @dblclick.stop="focusWidget(widget)"
                @contextmenu.prevent.stop="openContextMenu($event, widget)"
              >
                <div
                  class="widget-selection-label"
                  v-if="selectedId === widget.id"
                >
                  {{
                    widget.name ||
                    widget.style?.title ||
                    meta(widget.type).label
                  }}
                </div>
                <div
                  v-if="isWidgetTitleVisible(widget)"
                  class="widget-preview-heading"
                  :style="widgetHeadingStyle(widget)"
                >
                  <span v-if="previewWidgetTitle(widget)">{{
                    previewWidgetTitle(widget)
                  }}</span
                  ><small
                    v-if="dashboardTitleText(widget.style?.subtitle)"
                    :style="{
                      color: widget.style?.subtitleColor || '#aebccc',
                      fontSize: `${Number(widget.style?.subtitleFontSize) || 10}px`,
                      fontWeight:
                        Number(widget.style?.subtitleFontWeight) || 400,
                    }"
                    >{{ dashboardTitleText(widget.style?.subtitle) }}</small
                  >
                </div>
                <div
                  class="widget-preview-content"
                  :class="{
                    'icon-preview-content': widget.type === 'icon',
                    'metric-preview-content': [
                      'metric-card',
                      'number-flip',
                    ].includes(widget.type),
                  }"
                >
                  <template
                    v-if="
                      widget.type === 'metric-card' ||
                      widget.type === 'number-flip'
                    "
                  >
                    <span
                      v-if="
                        widget.type === 'number-flip' &&
                        widget.style?.flipSplitDigits === true
                      "
                      class="preview-flip-cells"
                      ><b
                        v-for="(character, index) in previewFlipCharacters(
                          widget,
                        )"
                        :key="`${widget.id}-${index}`"
                        >{{ character }}</b
                      ></span
                    ><span v-else class="preview-kpi">{{
                      previewMetric(widget)
                    }}</span
                    ><small>{{ widget.style?.unit || "" }}</small>
                  </template>
                  <template v-else-if="widget.type === 'statistics'"
                    ><div
                      class="preview-statistics"
                      :class="[
                        `stats-${widget.style?.statsMode || 'card'}`,
                        `stats-layout-${widget.style?.statsLayout || 'vertical'}`,
                        {
                          'stats-label-after':
                            widget.style?.statsLabelPosition === 'after',
                        },
                      ]"
                    >
                      <div class="preview-statistics-main">
                        <span v-if="dashboardStatisticsBodyTitleVisible(widget, previewWidgetTitle(widget))" class="preview-statistics-label">{{
                          previewStatistic(
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
                          }}{{ previewStatistic(widget, "value", "—")
                          }}<small class="preview-statistics-unit">{{
                            previewStatistic(
                              widget,
                              "suffix",
                              widget.style?.unit || "",
                            )
                          }}</small></strong
                        >
                      </div>
                      <em
                        v-if="widget.style?.statsShowCompare !== false"
                        :class="previewStatisticsCompareClass(widget)"
                        >{{ previewStatisticsCompareText(widget) }}</em
                      >
                    </div></template
                  >
                  <DashboardCarousel v-else-if="widget.type === 'carousel'" :rows="previewCarouselRows(widget)" :columns="previewColumns(widget)" :options="widget.style || {}" :format-value="previewValue" />
                  <template v-else-if="widget.type === 'gantt-chart'">
                    <DashboardGantt :rows="widgetPreviewRows(widget)" :field-map="widget.binding?.fieldMap || {}" :options="widget.style || {}" />
                  </template>
                  <template v-else-if="widget.type === 'milestone-timeline'"
                    ><div
                      class="preview-milestone-timeline"
                      :class="{
                        'labels-above':
                          widget.style?.timelineLabelPosition === 'above',
                        'layout-spread':
                          widget.style?.timelineLayout === 'spread',
                      }"
                    >
                      <div
                        v-for="(item, index) in previewMilestones(widget)"
                        :key="index"
                        :class="{ active: item.active, done: item.done }"
                      >
                        <i></i><b>{{ item.label }}</b
                        ><span
                          v-if="widget.style?.timelineShowDates !== false"
                          >{{ item.startDate || "—" }}</span
                        ><small
                          v-if="widget.style?.timelineShowEndDate === true"
                          >{{ item.endDate || "—" }}</small
                        >
                      </div>
                    </div></template
                  >
                  <template v-else-if="widget.type === 'access-list'"
                    ><div
                      class="preview-access-list"
                      :class="`access-${widget.style?.accessVariant || 'person'}`"
                    >
                      <div
                        v-for="(row, index) in previewAccessRows(widget)"
                        :key="row.id || index"
                        class="preview-access-row"
                      >
                        <img
                          v-if="
                            widget.style?.accessShowAvatar !== false &&
                            previewAccessValue(widget, row, 'avatar')
                          "
                          :src="
                            dashboardResourceUrl(
                              previewAccessValue(widget, row, 'avatar'),
                            )
                          "
                          alt=""
                        />
                        <div class="preview-access-copy">
                          <div class="preview-access-primary">
                            <b>{{
                              previewAccessValue(widget, row, "title") || "—"
                            }}</b
                            ><span>{{
                              previewAccessValue(widget, row, "subtitle")
                            }}</span
                            ><time>{{
                              previewAccessValue(widget, row, "time")
                            }}</time>
                          </div>
                          <small>{{
                            previewAccessValue(widget, row, "company") || "—"
                          }}</small>
                        </div>
                        <em
                          :class="{
                            success: previewAccessStatusSuccess(widget, row),
                            neutral: !previewAccessStatusSuccess(widget, row),
                          }"
                          >{{ previewAccessStatusText(widget, row) }}</em
                        >
                      </div>
                      <div
                        v-if="!previewAccessRows(widget).length"
                        class="preview-access-empty"
                      >
                        暂无进出场记录
                      </div>
                    </div></template
                  >
                  <template v-else-if="widget.type === 'current-time'"
                    ><div class="preview-clock-content">
                      <el-icon v-if="widget.style?.clockShowIcon !== false"
                        ><Clock /></el-icon
                      ><strong>{{ previewCurrentTime(widget) }}</strong>
                    </div></template
                  >
                  <template v-else-if="widget.type === 'weather'"
                    ><span class="preview-weather"
                      ><Cloudy /> {{ previewWeather(widget, "temperature", "—")
                      }}{{ widget.style?.temperatureUnit || "℃" }}</span
                    ><small
                      >{{
                        previewWeather(widget, "condition", "待接入天气数据")
                      }}
                      ·
                      {{ previewWeather(widget, "city", "未设置城市") }}</small
                    ></template
                  >
                  <template v-else-if="widget.type === 'color-block'"
                    ><span
                      class="preview-color-block"
                      :style="{
                        background:
                          previewColorBlockValue(widget),
                      }"
                    ></span
                    ><small>{{
                      previewStatistic(
                        widget,
                        "label",
                        widget.style?.blockLabel || "状态颜色",
                      )
                    }}</small></template
                  >
                  <template
                    v-else-if="
                      widget.type === 'text' || widget.type === 'rich-text'
                    "
                    ><span
                      class="preview-text"
                      :style="{ color: widget.style?.color }"
                      >{{ dashboardTitleText(widget.style?.text) }}</span
                    ></template
                  >
                  <template v-else-if="widget.type === 'icon'">
                    <DashboardIcon :options="widget.style" :name="widget.name || '图标'" />
                  </template>
                  <template v-else-if="widget.type === 'button'"
                    ><span class="preview-button" :style="dashboardButtonStyle(widget.style, dashboardResourceUrl(widget.style?.titleImageRef))">{{
                      dashboardButtonLabel(widget.style)
                    }}</span></template
                  >
                  <DashboardTabs v-else-if="widget.type === 'tabs'" :tabs="previewTabs(widget)" :fallback="widget.style?.tabContent || ''" />
                  <DashboardFilterForm v-else-if="['filter-form', 'designer-form', 'online-form'].includes(widget.type)" :fields="previewFormFields(widget)" />
                  <template v-else-if="widget.type === 'image'"
                    ><img
                      v-if="widget.style?.imageRef"
                      class="designer-image-preview"
                      :src="dashboardResourceUrl(widget.style.imageRef)"
                      :style="{
                        objectFit: widget.style?.imageFit || 'contain',
                        objectPosition: widget.style?.imagePosition || 'center',
                      }"
                      alt=""
                      draggable="false"
                    /><template v-else
                      ><el-icon class="preview-image"><Picture /></el-icon
                      ><span>请选择图库资源</span></template
                    ></template
                  >
                  <template v-else-if="widget.type === 'video'">
                    <video :key="`${widget.id}-${widget.style?.videoRef}-${widget.style?.autoplay}`" v-if="widget.style?.videoRef" class="designer-video-preview"
                      :src="dashboardResourceUrl(widget.style.videoRef)" :poster="dashboardResourceUrl(widget.style.posterRef)"
                      :autoplay="widget.style.autoplay" @loadeddata="dashboardVideoAutoplay($event, widget.style)" :loop="widget.style.loop" :muted="widget.style.muted !== false" :controls="widget.style.controls !== false" />
                    <span v-else>请选择平台视频资源</span>
                  </template>
                  <DashboardEmbeddedPage v-else-if="widget.type === 'iframe'" :src="widget.style?.iframeRef" />
                  <template v-else-if="widget.type === 'custom-html'"
                    ><iframe
                      class="custom-html-design-frame"
                      :srcdoc="customHtmlPreview(widget)"
                      sandbox="allow-scripts"
                      title="自定义内容预览"
                      tabindex="-1"
                    ></iframe
                  ></template>
                  <template v-else-if="isMapWidget(widget.type)">
                    <DashboardMapPreview
                      :widget="widget"
                      :rows="widgetPreviewRows(widget)"
                      :palette="paletteOptions.find((item) => item.key === schema.canvas.palette)?.colors || paletteOptions[0].colors"
                      :format-value="(field, value) => previewValue(value, previewField(widget, field))"
                    />
                  </template>
                  <template v-else-if="widget.type === 'ring-text'"
                    ><DashboardRingText :items="previewRingText(widget)" :options="widget.style || {}" :center-text="String(previewStatistic(widget, 'value', '工程驾驶舱'))" /></template
                  >
                  <template v-else-if="widget.type === 'word-cloud'"
                    ><div class="preview-word-cloud">
                      <span
                        v-for="(item, index) in previewWordCloud(widget)"
                        :key="`${item.label}-${index}`"
                        :style="{
                          fontSize: `${item.size}px`,
                          color: item.color,
                        }"
                        >{{ item.label }}</span
                      >
                    </div></template
                  >
                  <template v-else-if="widget.type === 'border'"></template>
                  <template v-else-if="widget.type === 'decoration'"
                    ><span class="preview-decoration"></span
                  ></template>
                  <DashboardDataTable
                    v-else-if="widget.type === 'table'"
                    :rows="widgetPreviewRows(widget)" :columns="previewColumns(widget)"
                    :auto-scroll="widget.style?.tableAutoScroll === true"
                    :seconds-per-row="widget.style?.tableScrollSeconds"
                    :format-value="previewValue" :interactive="false"
                  />
                  <template
                    v-else-if="
                      [
                        'table',
                        'advanced-table',
                        'rank-table',
                        'carousel-table',
                        'alert-list',
                        'realtime-list',
                      ].includes(widget.type)
                    "
                    ><div
                      class="designer-table-preview"
                      :class="{
                        stripe:
                          widget.type === 'advanced-table' &&
                          widget.style?.advancedStripe !== false,
                      }"
                      :style="{
                        '--preview-row-height': `${widget.type === 'advanced-table' ? Number(widget.style?.advancedRowHeight) || 34 : 30}px`,
                      }"
                    >
                      <table>
                        <thead
                          v-if="
                            widget.type !== 'advanced-table' ||
                            widget.style?.advancedShowHeader !== false
                          "
                        >
                          <tr>
                            <th
                              v-if="
                                widget.type === 'advanced-table' &&
                                widget.style?.advancedShowIndex
                              "
                            >
                              #
                            </th>
                            <th
                              v-for="(column, columnIndex) in previewColumns(widget)"
                              :key="column.name"
                              :class="{
                                'table-primary-column':
                                  widget.type === 'advanced-table' &&
                                  columnIndex === 0,
                              }"
                            >
                              {{ column.title || column.name }}
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr
                            v-for="(row, index) in previewTableRows(widget)"
                            :key="row.id || index"
                          >
                            <td
                              v-if="
                                widget.type === 'advanced-table' &&
                                widget.style?.advancedShowIndex
                              "
                            >
                              {{ index + 1 }}
                            </td>
                            <td
                              v-for="(column, columnIndex) in previewColumns(widget)"
                              :key="column.name"
                              :class="{
                                'table-primary-column':
                                  widget.type === 'advanced-table' &&
                                  columnIndex === 0,
                              }"
                            >
                              <span :style="dashboardTableCellStyle(widget.style?.tableRules, column.name, row[column.name])">{{ previewValue(row[column.name], column) }}{{ dashboardTableSuffix(widget.style?.tableRules, column.name, row[column.name]) }}</span>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div></template
                  >
                  <template
                    v-else-if="
                      isChart(widget.type) && widget.type !== 'word-cloud'
                    "
                    ><div
                      :ref="(el) => setDesignChartRef(widget.id, el)"
                      class="designer-chart-preview"
                    ></div
                  ></template>
                  <template v-else
                    ><div
                      class="preview-chart"
                      :class="`preview-${widget.type}`"
                    >
                      <i
                        v-for="n in 7"
                        :key="n"
                        :style="{
                          height: `${22 + ((n * 17 + widget.id.length) % 48)}%`,
                        }"
                      ></i></div
                  ></template>
                </div>
                <div
                  v-if="widgetPreviewStatus(widget)"
                  class="designer-data-preview-state"
                >
                  {{ widgetPreviewStatus(widget) }}
                </div>
                <span
                  v-if="
                    selectedIds.length === 1 && !selectedGroupId && selectedIds.includes(widget.id) &&
                    selectedId === widget.id &&
                    !widget.state?.locked
                  "
                  v-for="handle in resizeHandles"
                  :key="handle"
                  class="resize-handle"
                  :class="`handle-${handle}`"
                  @pointerdown.stop="startResize($event, widget, handle)"
                ></span>
              </div>
              <div v-if="selectedGroupId && selectedGroupBounds" class="canvas-group-selection" :class="{ locked: selectedHasLocked }" :style="selectedGroupStyle" aria-label="组合选框">
                <span class="group-selection-label" @pointerdown.stop="startMove($event, selectedLayoutWidgets[0])">{{ groupName(selectedGroupId) }}{{ selectedHasLocked ? '（已锁定）' : '' }} · {{ selectedIds.length }}</span>
                <span v-if="!selectedHasLocked" v-for="handle in resizeHandles" :key="handle" class="resize-handle" :class="`handle-${handle}`" @pointerdown.stop="startGroupResize($event, handle)"></span>
              </div>
              <div v-if="marquee.active && marquee.moved" class="canvas-selection-box" :style="marqueeStyle" aria-hidden="true"></div>
              <div v-if="!schema.widgets.length" class="canvas-empty">
                <el-icon><Plus /></el-icon><span>从左侧选择组件开始设计</span
                ><small>组件会出现在画布中央</small>
              </div>
            </div>
          </div>
        </div>

        <footer class="workspace-footer">
          <span
            ><span class="status-dot" :class="{ dirty: draftDirty }"></span
            >{{ draftDirty ? "有未保存修改" : "所有修改已保存" }}</span
          >
          <span class="footer-hint"
            >空白处拖拽框选 · Shift / Ctrl / ⌘ + 点击多选 · 空格拖动平移 · Delete 删除</span
          >
        </footer>
      </section>

      <!-- 属性表单即时更新配置，禁止原生提交触发整页导航；保留控件自身的回车行为。 -->
      <aside class="inspector-panel" @submit.prevent>
        <template v-if="selectedIds.length > 1 || selectedGroupId">
          <div class="inspector-head">
            <div>
              <span class="inspector-eyebrow">{{ selectedGroupId ? '组合属性' : '批量编辑' }}</span>
              <h2>{{ selectedGroupId ? groupName(selectedGroupId) : '已选' }} · {{ selectedIds.length }} 个组件</h2>
            </div>
            <el-button text circle @click="clearSelection"
              ><el-icon><Close /></el-icon
            ></el-button>
          </div>
          <div class="inspector-scroll">
            <div class="property-section">
              <div class="section-label">公共样式</div>
              <div class="color-row">
                <label>主色</label
                ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                  :model-value="batchStyle.color"
                  @active-change="(value) => updateBatchStyleColor('color', value)"
                  @change="(value) => finishBatchStyleColor('color', value)"
                />
              </div>
              <div class="color-row">
                <label>背景</label
                ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                  :model-value="batchStyle.backgroundColor"
                  show-alpha
                  @active-change="(value) => updateBatchStyleColor('backgroundColor', value)"
                  @change="(value) => finishBatchStyleColor('backgroundColor', value)"
                />
              </div>
              <label class="range-field"
                >透明度
                <span
                  >{{
                    Math.round(Number(batchStyle.opacity ?? 1) * 100)
                  }}%</span
                ><el-slider
                  :model-value="Number(batchStyle.opacity ?? 1) * 100"
                  :min="0"
                  :max="100"
                  @update:model-value="
                    (value) =>
                      updateBatchStyleSlider('opacity', Number(value) / 100)
                  "
                  @change="finishSliderChange" /></label
              ><label class="range-field"
                >圆角 <span>{{ batchStyle.borderRadius || 0 }}px</span
                ><el-slider
                  :model-value="Number(batchStyle.borderRadius || 0)"
                  :min="0"
                  :max="32"
                  @update:model-value="
                    (value) =>
                      updateBatchStyleSlider('borderRadius', Number(value))
                  "
                  @change="finishSliderChange"
              /></label>
            </div>
            <div class="property-section toggle-section">
              <div class="toggle-row">
                <span
                  ><el-icon><View /></el-icon>全部显示</span
                ><el-switch
                  :model-value="batchAllVisible"
                  @change="(value) => applyBatchState('visible', value)"
                />
              </div>
              <div class="toggle-row">
                <span
                  ><el-icon><Lock /></el-icon>全部锁定</span
                ><el-switch
                  :model-value="batchAllLocked"
                  @change="(value) => applyBatchState('locked', value)"
                />
              </div>
            </div>
            <div class="data-quality-note">
              <el-icon><InfoFilled /></el-icon
              ><span
                >使用顶部工具栏排列组件；此处调整公共样式和状态。数据绑定、交互和组件专有属性仍需单独编辑。</span
              >
            </div>
          </div>
        </template>
        <template v-else-if="selectedWidget">
          <div class="inspector-head">
            <div>
              <span class="inspector-eyebrow">{{ memberEditId ? '组内组件属性' : '组件属性' }}</span>
              <h2>
                {{
                  selectedWidget.name ||
                  selectedWidget.style?.title ||
                  meta(selectedWidget.type).label
                }}
              </h2>
            </div>
            <div class="inspector-head-actions">
              <el-button link type="primary" @click="componentGuideVisible = true">数据说明</el-button>
              <el-button text circle @click="duplicateSelected"
                ><el-icon><CopyDocument /></el-icon></el-button
              ><el-button text circle type="danger" @click="deleteSelected"
                ><el-icon><Delete /></el-icon
              ></el-button>
            </div>
          </div>
          <el-tabs v-model="inspectorTab" class="inspector-tabs">
            <el-tab-pane label="基础" name="basic">
              <div class="inspector-scroll">
                <div
                  v-if="isMapWidget(selectedWidget.type)"
                  class="property-section map-resource-section"
                >
                  <div class="section-label">地图资源</div>
                  <el-form label-position="top">
                    <el-form-item label="平台 GeoJSON 资源"
                      ><DesignerTreeSelect
                        v-model="selectedWidget.style.mapRef"
                        :data="mapPickerTree"
                        :props="resourceTreeProps"
                        node-key="value"
                        check-strictly
                        filterable
                        clearable
                        render-after-expand
                        placeholder="选择平台地图"
                        empty-text="暂无可选地图"
                        @change="markDirty"
                      />
                      <p class="field-hint">
                        仅允许平台审核的
                        GeoJSON；地图数据仍通过数据集字段映射提供。
                      </p></el-form-item
                    >
                  </el-form>
                </div>
                <div class="property-section">
                  <div class="section-label">标识</div>
                  <el-form label-position="top">
                    <el-form-item label="图层名称"
                      ><el-input
                        v-model="selectedWidget.name"
                        placeholder="组件名称"
                        @focus="beginHistory"
                        @change="endHistory"
                    /></el-form-item>
                    <el-form-item label="组件类型"
                      ><el-input
                        :model-value="meta(selectedWidget.type).label"
                        disabled
                        ><template #prefix
                          ><el-icon
                            ><component
                              :is="
                                meta(selectedWidget.type).icon
                              " /></el-icon></template></el-input
                    ></el-form-item>
                  </el-form>
                </div>
                <div class="property-section">
                  <div class="section-label">位置与尺寸</div>
                  <div class="position-grid">
                    <label
                      >X
                      <el-input-number
                        :model-value="selectedWidget.layout.x"
                        :min="0"
                        :max="canvasWidth - selectedWidget.layout.w"
                        controls-position="right"
                        @focus="beginHistory"
                        @change="(value) => setLayout('x', value)"
                    /></label>
                    <label
                      >Y
                      <el-input-number
                        :model-value="selectedWidget.layout.y"
                        :min="0"
                        :max="canvasHeight - selectedWidget.layout.h"
                        controls-position="right"
                        @focus="beginHistory"
                        @change="(value) => setLayout('y', value)"
                    /></label>
                    <label
                      >宽
                      <el-input-number
                        :model-value="selectedWidget.layout.w"
                        :min="40"
                        :max="canvasWidth"
                        controls-position="right"
                        @focus="beginHistory"
                        @change="(value) => setLayout('w', value)"
                    /></label>
                    <label
                      >高
                      <el-input-number
                        :model-value="selectedWidget.layout.h"
                        :min="40"
                        :max="canvasHeight"
                        controls-position="right"
                        @focus="beginHistory"
                        @change="(value) => setLayout('h', value)"
                    /></label>
                  </div>
                  <label class="range-field"
                    >旋转 <span>{{ selectedWidget.layout.rotate || 0 }}°</span
                    ><el-slider
                      :model-value="selectedWidget.layout.rotate || 0"
                      :min="-180"
                      :max="180"
                      :step="1"
                      @update:model-value="
                        (value) => updateLayoutSlider('rotate', value)
                      "
                      @change="finishSliderChange"
                  /></label>
                </div>
                <div class="property-section toggle-section">
                  <div class="toggle-row">
                    <span
                      ><el-icon><View /></el-icon>显示组件</span
                    ><el-switch
                      :model-value="selectedWidget.state?.visible !== false"
                      @change="toggleVisible(selectedWidget, memberEditId === selectedWidget.id)"
                    />
                  </div>
                  <div class="toggle-row">
                    <span
                      ><el-icon><Lock /></el-icon>锁定位置</span
                    ><el-switch
                      :model-value="selectedWidget.state?.locked === true"
                      @change="toggleLocked(selectedWidget, memberEditId === selectedWidget.id)"
                    />
                  </div>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane v-if="selectedCapabilities.data" label="数据" name="data">
              <div class="inspector-scroll">
                <div class="property-section">
                  <div class="section-label">数据集绑定</div>
                  <el-form label-position="top">
                    <el-form-item label="数据来源"
                      ><el-radio-group
                        :model-value="
                          selectedWidget.binding?.sourceType === 'STATIC'
                            ? 'STATIC'
                            : 'DATASET'
                        "
                        @change="setWidgetSource"
                        ><el-radio-button value="DATASET"
                          >数据集</el-radio-button
                        ><el-radio-button value="STATIC"
                          >静态数据</el-radio-button
                        ></el-radio-group
                      ></el-form-item
                    >
                    <el-form-item
                      v-if="selectedWidget.binding?.sourceType !== 'STATIC'"
                      label="数据集"
                      ><DesignerTreeSelect
                        :model-value="selectedWidget.binding?.datasetCode || ''"
                        :data="datasetPickerTree"
                        :props="datasetTreeProps"
                        node-key="value"
                        check-strictly
                        clearable
                        filterable
                        render-after-expand
                        placeholder="选择数据集"
                        empty-text="暂无可选数据集"
                        @change="setDataset"
                      />
                    </el-form-item>
                  </el-form>
                  <div
                    v-if="selectedWidget.binding?.sourceType === 'STATIC'"
                    class="static-data-editor"
                  >
                    <el-input
                      v-model="staticRowsEditorText"
                      type="textarea"
                      :rows="7"
                      spellcheck="false"
                      @focus="beginHistory"
                      @change="setStaticRowsJson"
                      @blur="setStaticRowsJson"
                    />
                    <p class="field-hint">
                      静态数据只保存基础值数组，最多 1000
                      行；不请求外部来源，适合官方设计器中的静态数据源场景。
                    </p>
                  </div>
                  <div v-if="selectedDataset" class="dataset-summary">
                    <span
                      class="dataset-status"
                      :class="`status-${selectedDataset.status?.toLowerCase()}`"
                    ></span
                    ><span>{{ selectedDataset.datasetName }}</span
                    ><small>{{ selectedDataset.dataType }}</small>
                  </div>
                  <el-form
                    v-if="
                      selectedDataset ||
                      selectedWidget.binding?.sourceType === 'STATIC'
                    "
                    label-position="top"
                    class="widget-limit-form"
                    ><el-form-item v-if="selectedPropertyVisible('binding.rowLimit')" label="组件展示条数"
                      ><el-input-number
                        :model-value="selectedWidget.binding.rowLimit || 50"
                        :min="1"
                        :max="1000"
                        controls-position="right"
                        @focus="beginHistory"
                        @change="setWidgetRowLimit"
                      />
                      <p class="field-hint">
                        只限制当前组件展示，不改变数据集总行数。
                      </p></el-form-item
                    ><el-form-item v-if="selectedDataset && selectedDataset.dataType !== 'WEBSOCKET'" label="组件数据刷新间隔"
                      ><div class="refresh-inherit">
                        <el-input-number
                          :model-value="
                            selectedWidget.binding.refreshSeconds || 0
                          "
                          :min="0"
                          :step="5"
                          :precision="0"
                          :max="3600"
                          controls-position="right"
                          @focus="beginHistory"
                          @change="setWidgetRefresh"
                        /><span>{{
                          selectedWidget.binding.refreshSeconds
                            ? "秒"
                            : "跟随页面"
                        }}</span>
                      </div>
                      <p class="field-hint">
                        0 表示跟随页面默认规则；5–3600 秒按组件自身周期更新数据，不受页面默认刷新开关影响。
                      </p>
                      <p v-if="!selectedWidget.binding.refreshSeconds && schema.refresh.enabled === false" class="field-hint">当前页面默认刷新已关闭，此组件不会定时取数。</p></el-form-item
                    ></el-form
                  >
                </div>
                <div
                  v-if="
                    selectedDataset ||
                    selectedWidget.binding?.sourceType === 'STATIC'
                  "
                  class="property-section"
                >
                  <div class="section-label">字段映射</div>
                  <p v-if="selectedWidget.type === 'statistics'" class="field-hint">标题和后缀：映射字段有值时优先显示；为空或缺失时使用样式中的手工内容。清空映射可固定使用手工内容。</p>
                  <div
                    v-for="role in fieldRoles"
                    :key="role.key"
                    class="mapping-row"
                  >
                    <label>{{ role.label }}</label
                    ><el-select
                      :model-value="
                        selectedWidget.binding.fieldMap?.[role.key] || ''
                      "
                      clearable
                      :placeholder="role.placeholder || '未设置'"
                      @change="(value) => setFieldMap(role.key, value)"
                      ><el-option
                        v-for="field in datasetFields"
                        :key="field.name"
                        :label="field.title || field.name"
                        :value="field.name"
                    /></el-select>
                  </div>
                  <div
                    v-if="selectedCapabilities.multiSeries"
                    class="mapping-row mapping-row-series"
                  >
                    <label>数值系列</label
                    ><el-select
                      :model-value="
                        selectedWidget.binding.fieldMap?.valueFields || []
                      "
                      multiple
                      clearable
                      collapse-tags
                      collapse-tags-tooltip
                      placeholder="单系列时使用数值字段"
                      @change="(value) => setFieldMap('valueFields', value)"
                      ><el-option
                        v-for="field in numericDatasetFields"
                        :key="field.name"
                        :label="field.title || field.name"
                        :value="field.name"
                    /></el-select>
                  </div>
                  <p
                    v-if="selectedCapabilities.multiSeries"
                    class="field-hint"
                  >
                    可选择多个数值字段生成多系列图表；未选择时使用“数值字段”。
                  </p>
                  <el-form
                    v-if="
                      [
                        'table',
                        'advanced-table',
                        'carousel',
                        'rank-table',
                        'carousel-table',
                        'alert-list',
                        'realtime-list',
                      ].includes(selectedWidget.type)
                    "
                    label-position="top"
                    ><el-form-item label="展示字段"
                      ><el-select
                        v-model="selectedWidget.binding.displayFields"
                        multiple
                        clearable
                        collapse-tags
                        placeholder="默认显示所有可见字段"
                        @change="markDirty"
                        ><el-option
                          v-for="field in datasetFields"
                          :key="field.name"
                          :label="field.title || field.name"
                          :value="field.name"
                      /></el-select>
                      <p class="field-hint">
                        可调整字段顺序；留空时按数据集字段定义显示。
                      </p></el-form-item
                    ></el-form
                  >
                  <p v-if="!datasetFields.length" class="field-hint">
                    该数据源尚未声明字段；静态数据会根据首行字段生成候选，数据集则需先解析字段。
                  </p>
                </div>
                <div
                  v-if="['metric-card', 'statistics', 'number-flip'].includes(selectedWidget.type)"
                  class="property-section"
                >
                  <div class="section-label">数值格式</div>
                  <el-form label-position="top">
                    <el-form-item label="数值数量级"><el-select v-model="selectedWidget.style.chartConfig.valueScale" @change="markDirty">
                      <el-option label="不缩放" value="none" /><el-option label="千" value="thousand" /><el-option label="万" value="ten-thousand" /><el-option label="百万" value="million" />
                    </el-select></el-form-item>
                    <el-form-item label="小数位数"
                      ><el-select
                        v-model="selectedWidget.style.valueDecimalPlaces"
                        clearable
                        placeholder="跟随数据"
                        @change="markDirty"
                        ><el-option
                          v-for="n in 7"
                          :key="n"
                          :label="`${n - 1} 位小数`"
                          :value="n - 1"
                      /></el-select
                    ></el-form-item>
                  </el-form>
                  <p class="field-hint">留空时使用数据原值；设置后仅影响当前组件的数值显示。</p>
                </div>
                <div v-if="selectedCapabilities.chart" class="property-section">
                  <div class="section-label">数值格式</div>
                  <div class="style-grid">
                    <el-form-item label="数值数量级"
                      ><el-select
                        v-model="selectedWidget.style.chartConfig.valueScale"
                        @change="markDirty"
                        ><el-option label="不缩放" value="none" /><el-option
                          label="千"
                          value="thousand" /><el-option
                          label="万"
                          value="ten-thousand" /><el-option
                          label="百万"
                          value="million" /></el-select
                    ></el-form-item>
                    <el-form-item label="小数位数"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.valuePrecision
                        "
                        :min="0"
                        :max="6"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                </div>
                <div
                  v-if="selectedDataset && datasetParameters.length"
                  class="property-section"
                >
                  <div class="section-label">参数默认值</div>
                  <div
                    v-for="param in datasetParameters"
                    :key="param.name"
                    class="param-row"
                  >
                    <label
                      >{{ param.title || param.name
                      }}<small>{{ param.type }}</small></label
                    ><el-input
                      :model-value="
                        selectedWidget.binding.parameters?.[param.name] || ''
                      "
                      :placeholder="
                        param.default == null
                          ? '运行时传入'
                          : String(param.default)
                      "
                      @focus="beginHistory"
                      @change="(value) => setParameter(param.name, value)"
                    />
                  </div>
                </div>
                <div v-if="selectedDataset" class="property-section">
                  <div class="section-label section-label-inline">
                    <span>过滤条件</span
                    ><el-button text type="primary" @click="addFilter"
                      >+ 添加</el-button
                    >
                  </div>
                  <div
                    v-for="(filter, index) in selectedWidget.binding.filters ||
                    []"
                    :key="filter.id || index"
                    class="filter-row"
                  >
                    <el-input
                      v-model="filter.field"
                      placeholder="字段"
                      @focus="beginHistory"
                      @change="endHistory"
                    /><el-select v-model="filter.operator" @change="markDirty"
                      ><el-option label="等于" value="eq" /><el-option
                        label="不等于"
                        value="ne" /><el-option
                        label="包含"
                        value="contains" /><el-option
                        label="大于"
                        value="gt" /><el-option
                        label="大于等于"
                        value="gte" /><el-option
                        label="小于"
                        value="lt" /><el-option
                        label="小于等于"
                        value="lte" /><el-option
                        label="为空"
                        value="isnull" /><el-option
                        label="不为空"
                        value="notnull" /></el-select
                    ><el-input
                      v-if="!['isnull', 'notnull'].includes(filter.operator)"
                      v-model="filter.value"
                      placeholder="值"
                      @focus="beginHistory"
                      @change="endHistory"
                    /><span v-else class="filter-no-value">无需填写值</span
                    ><el-button text type="danger" @click="removeFilter(index)"
                      ><el-icon><Close /></el-icon
                    ></el-button>
                  </div>
                </div>
                <div v-if="selectedDataset" class="data-quality-note">
                  <el-icon><InfoFilled /></el-icon
                  ><span>数据由服务端受控执行，组件不会直接访问原始来源。</span>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="样式" name="style">
              <div class="inspector-scroll">
                <div
                  v-if="isMapWidget(selectedWidget.type)"
                  class="property-section"
                >
                  <div class="section-label">地图资源与显示</div>
                  <div class="toggle-row">
                    <span
                      >当前资源：{{
                        selectedWidget.style.mapRef || "未配置"
                      }}</span
                    ><el-button
                      plain
                      @click="
                        setStyle(
                          'mapRef',
                          '/dashboard/assets/map/demo-region.json',
                        )
                      "
                      >使用内置示例</el-button
                    >
                  </div>
                  <div class="style-grid">
                    <el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.mapAreaColor')" label="地图区域色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapAreaColor"
                        @active-change="(value) => updateStyleColor('mapAreaColor', value)"
                        @change="(value) => finishStyleColor('mapAreaColor', value)" /></el-form-item
                    ><el-form-item class="color-property-field" label="地图边界色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapBorderColor"
                        @active-change="(value) => updateStyleColor('mapBorderColor', value)"
                        @change="(value) => finishStyleColor('mapBorderColor', value)" /></el-form-item
                    ><el-form-item label="边界宽度"
                      ><el-input-number
                        v-model="selectedWidget.style.mapBorderWidth"
                        :min="0"
                        :max="10"
                        :step="0.1"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item class="color-property-field" label="高亮区域色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapEmphasisColor"
                        @active-change="(value) => updateStyleColor('mapEmphasisColor', value)"
                        @change="(value) => finishStyleColor('mapEmphasisColor', value)"
                    /></el-form-item>
                  </div>
                  <div class="toggle-row">
                    <span>区域渐变色</span
                    ><el-switch
                      v-model="selectedWidget.style.mapAreaGradient"
                      @change="markDirty"
                    />
                  </div>
                  <div
                    v-if="selectedWidget.style.mapAreaGradient"
                    class="style-grid"
                  >
                    <el-form-item class="color-property-field" label="中心颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapCenterColor"
                        @active-change="(value) => updateStyleColor('mapCenterColor', value)"
                        @change="(value) => finishStyleColor('mapCenterColor', value)" /></el-form-item
                    ><el-form-item class="color-property-field" label="边缘颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapEdgeColor"
                        @active-change="(value) => updateStyleColor('mapEdgeColor', value)"
                        @change="(value) => finishStyleColor('mapEdgeColor', value)"
                    /></el-form-item>
                  </div>
                  <div class="style-grid">
                    <el-form-item label="缩放比例"
                      ><el-input-number
                        v-model="selectedWidget.style.mapZoom"
                        :min="0.5"
                        :max="20"
                        :step="0.1"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="地图长宽比"
                      ><el-input-number
                        v-model="selectedWidget.style.mapAspectScale"
                        :min="0.5"
                        :max="2"
                        :step="0.1"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="中心 X（%）"
                      ><el-input-number
                        v-model="selectedWidget.style.mapLayoutX"
                        :min="0"
                        :max="100"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="中心 Y（%）"
                      ><el-input-number
                        v-model="selectedWidget.style.mapLayoutY"
                        :min="0"
                        :max="100"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="布局大小（%）"
                      ><el-input-number
                        v-model="selectedWidget.style.mapLayoutSize"
                        :min="10"
                        :max="200"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="点位大小"
                      ><el-input-number
                        v-model="selectedWidget.style.mapPointSize"
                        :min="4"
                        :max="32"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div class="style-grid">
                    <el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.mapLabelColor')" label="区域名称颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapLabelColor"
                        @active-change="(value) => updateStyleColor('mapLabelColor', value)"
                        @change="(value) => finishStyleColor('mapLabelColor', value)" /></el-form-item
                    ><el-form-item v-if="selectedPropertyVisible('style.mapLabelFontSize')" label="区域名称字号"
                      ><el-input-number
                        v-model="selectedWidget.style.mapLabelFontSize"
                        :min="8"
                        :max="32"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="阴影大小"
                      ><el-input-number
                        v-model="selectedWidget.style.mapShadowBlur"
                        :min="0"
                        :max="100"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.mapShadowColor')" label="阴影颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapShadowColor"
                        show-alpha
                        @active-change="(value) => updateStyleColor('mapShadowColor', value)"
                        @change="(value) => finishStyleColor('mapShadowColor', value)" /></el-form-item
                    ><el-form-item v-if="selectedPropertyVisible('style.mapShadowOffsetX')" label="水平偏移"
                      ><el-input-number
                        v-model="selectedWidget.style.mapShadowOffsetX"
                        :min="-100"
                        :max="100"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item v-if="selectedPropertyVisible('style.mapShadowOffsetY')" label="垂直偏移"
                      ><el-input-number
                        v-model="selectedWidget.style.mapShadowOffsetY"
                        :min="-100"
                        :max="100"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div class="toggle-row">
                    <span>显示区域名称</span
                    ><el-switch
                      v-model="selectedWidget.style.mapShowLabels"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>允许缩放漫游</span
                    ><el-switch
                      v-model="selectedWidget.style.mapRoam"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>显示视觉映射</span
                    ><el-switch
                      v-model="selectedWidget.style.mapVisualMap"
                      @change="markDirty"
                    />
                  </div>
                  <div
                    v-if="selectedWidget.style.mapVisualMap || selectedWidget.type === 'map-heat'"
                    class="style-grid"
                  >
                    <el-form-item label="最小值"
                      ><el-input-number
                        v-model="selectedWidget.style.mapVisualMin"
                        :min="-1000000000000"
                        :max="1000000000000"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="最大值"
                      ><el-input-number
                        v-model="selectedWidget.style.mapVisualMax"
                        :min="-1000000000000"
                        :max="1000000000000"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item class="color-property-field" label="低值颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapVisualMinColor"
                        @active-change="(value) => updateStyleColor('mapVisualMinColor', value)"
                        @change="(value) => finishStyleColor('mapVisualMinColor', value)" /></el-form-item
                    ><el-form-item class="color-property-field" label="高值颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.mapVisualMaxColor"
                        @active-change="(value) => updateStyleColor('mapVisualMaxColor', value)"
                        @change="(value) => finishStyleColor('mapVisualMaxColor', value)"
                    /></el-form-item>
                  </div>
                  <div v-if="selectedWidget.type === 'map-timeline'" class="style-grid">
                    <el-form-item label="时间分组切换间隔（秒）"
                      ><el-input-number v-model="selectedWidget.style.carouselInterval" :min="2" :max="60" controls-position="right" @change="markDirty" /></el-form-item>
                  </div>
                  <p class="field-hint">
                    名称/数值字段用于区域填色和视觉映射；经纬度字段用于散点。区域点击可复用组件联动或逐级钻取配置。
                  </p>
                </div>
                <div
                  v-if="selectedWidget.type === 'number-flip'"
                  class="property-section"
                >
                  <div class="section-label">数字翻牌</div>
                  <div class="toggle-row">
                    <span>分格显示</span
                    ><el-switch
                      v-model="selectedWidget.style.flipSplitDigits"
                      @change="markDirty"
                    />
                  </div>
                  <div class="style-grid">
                    <el-form-item label="最少位数"
                      ><el-input-number
                        v-model="selectedWidget.style.flipMinDigits"
                        :min="1"
                        :max="12"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item v-if="selectedPropertyVisible('style.flipCellGap')" label="格间距"
                      ><el-input-number
                        v-model="selectedWidget.style.flipCellGap"
                        :min="0"
                        :max="32"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item v-if="selectedPropertyVisible('style.flipCellWidth')" label="单格宽度"
                      ><el-input-number
                        v-model="selectedWidget.style.flipCellWidth"
                        :min="18"
                        :max="96"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item v-if="selectedPropertyVisible('style.flipCellHeight')" label="单格高度"
                      ><el-input-number
                        v-model="selectedWidget.style.flipCellHeight"
                        :min="24"
                        :max="120"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div v-if="selectedPropertyVisible('style.flipCellBackground')" class="color-row">
                    <label>单格背景</label
                    ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                      :model-value="selectedWidget.style.flipCellBackground"
                      show-alpha
                      @active-change="(value) => updateStyleColor('flipCellBackground', value)"
                      @change="(value) => finishStyleColor('flipCellBackground', value)"
                    />
                  </div>
                  <div v-if="selectedPropertyVisible('style.flipCellBorderColor')" class="color-row">
                    <label>单格边框</label
                    ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                      :model-value="selectedWidget.style.flipCellBorderColor"
                      show-alpha
                      @active-change="(value) => updateStyleColor('flipCellBorderColor', value)"
                      @change="(value) => finishStyleColor('flipCellBorderColor', value)"
                    />
                  </div>
                </div>
                <div
                  v-if="selectedWidget.type === 'statistics'"
                  class="property-section"
                >
                  <div class="section-label">统计概览</div>
                  <div class="style-grid">
                    <el-form-item label="展示模式"
                      ><el-select
                        v-model="selectedWidget.style.statsMode"
                        @change="markDirty"
                        ><el-option label="卡片" value="card" /><el-option
                          label="紧凑"
                          value="compact" /></el-select></el-form-item
                    ><el-form-item label="数值字号"
                      ><el-input-number
                        v-model="selectedWidget.style.statsValueSize"
                        :min="16"
                        :max="72"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div class="style-grid">
                    <el-form-item v-if="selectedPropertyVisible('style.statsLayout')" label="标题布局"
                      ><el-select
                        v-model="selectedWidget.style.statsLayout"
                        @change="markDirty"
                        ><el-option label="上下布局" value="vertical" /><el-option
                          label="左右布局"
                          value="horizontal" /></el-select
                    ></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.statsLabelPosition')" label="标题位置"
                      ><el-select
                        v-model="selectedWidget.style.statsLabelPosition"
                        @change="markDirty"
                        ><el-option label="数值上方" value="before" /><el-option
                          label="数值下方"
                          value="after" /></el-select
                    ></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.statsLabelSize')" label="标题字号"
                      ><el-input-number
                        v-model="selectedWidget.style.statsLabelSize"
                        :min="9"
                        :max="36"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="单位字号"
                      ><el-input-number
                        v-model="selectedWidget.style.statsUnitSize"
                        :min="9"
                        :max="30"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div class="style-grid">
                    <el-form-item v-if="selectedPropertyVisible('style.statsLabelColor')" class="color-property-field" label="标题颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.statsLabelColor"
                        @active-change="(value) => updateStyleColor('statsLabelColor', value)"
                        @change="(value) => finishStyleColor('statsLabelColor', value)" /></el-form-item
                    ><el-form-item class="color-property-field" label="单位颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.statsUnitColor"
                        @active-change="(value) => updateStyleColor('statsUnitColor', value)"
                        @change="(value) => finishStyleColor('statsUnitColor', value)"
                    /></el-form-item>
                  </div>
                  <div class="toggle-row">
                    <span>显示对比值</span
                    ><el-switch
                      v-model="selectedWidget.style.statsShowCompare"
                      @change="markDirty"
                    />
                  </div>
                  <div class="style-grid">
                    <el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.statsUpColor')" label="上涨颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.statsUpColor"
                        @active-change="(value) => updateStyleColor('statsUpColor', value)"
                        @change="(value) => finishStyleColor('statsUpColor', value)" /></el-form-item
                    ><el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.statsDownColor')" label="下降颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.statsDownColor"
                        @active-change="(value) => updateStyleColor('statsDownColor', value)"
                        @change="(value) => finishStyleColor('statsDownColor', value)" /></el-form-item
                    ><el-form-item label="数值前缀"
                      ><el-input
                        v-model="selectedWidget.style.statsPrefix"
                        maxlength="8"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="数值后缀">
                      <el-input
                        v-model="selectedWidget.style.unit"
                        placeholder="例如 %、人、项"
                        maxlength="256"
                        @focus="beginHistory"
                        @change="endHistory"
                      />
                    </el-form-item>
                  </div>
                  <p class="field-hint">后缀仅追加文字，不改变数值。已绑定单位字段时优先使用字段值，字段为空时使用此后缀。</p>
                </div>
                <div
                  v-if="selectedWidget.type === 'carousel'"
                  class="property-section"
                >
                  <div class="section-label">卡片轮播</div>
                  <div class="style-grid">
                    <el-form-item label="切换间隔（秒）"
                      ><el-input-number
                        v-model="selectedWidget.style.carouselInterval"
                        :min="2"
                        :max="60"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="方向"
                      ><el-select
                        v-model="selectedWidget.style.carouselDirection"
                        @change="markDirty"
                        ><el-option label="横向" value="horizontal" /><el-option
                          label="纵向"
                          value="vertical" /></el-select
                    ></el-form-item>
                  </div>
                  <div class="toggle-row">
                    <span>显示序号</span
                    ><el-switch
                      v-model="selectedWidget.style.carouselShowIndex"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>高亮当前卡片</span
                    ><el-switch
                      v-model="selectedWidget.style.carouselHighlight"
                      @change="markDirty"
                    />
                  </div>
                </div>
                <div v-if="selectedWidget.type === 'gantt-chart'" class="property-section">
                  <div class="section-label">计划甘特图</div>
                  <el-form label-position="top">
                    <el-form-item label="时间轴起点"
                      ><el-date-picker
                        v-model="selectedWidget.style.ganttStart"
                        type="date"
                        value-format="YYYY-MM-DD"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="时间轴终点"
                      ><el-date-picker
                        v-model="selectedWidget.style.ganttEnd"
                        type="date"
                        value-format="YYYY-MM-DD"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="当前日期标线"
                      ><el-date-picker
                        v-model="selectedWidget.style.ganttCurrentDate"
                        type="date"
                        value-format="YYYY-MM-DD"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="任务名称宽度"
                      ><el-input-number
                        :model-value="Number(selectedWidget.style.ganttLabelWidth) || 140"
                        :min="80"
                        :max="400"
                        controls-position="right"
                        @change="(value) => setStyle('ganttLabelWidth', value)"
                    /></el-form-item>
                  </el-form>
                  <div class="style-grid">
                    <el-form-item class="color-property-field" label="计划颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.ganttPlanColor || '#becbd2'"
                        @active-change="(value) => updateStyleColor('ganttPlanColor', value)"
                        @change="(value) => finishStyleColor('ganttPlanColor', value)"
                    /></el-form-item>
                    <el-form-item class="color-property-field" label="实际颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.ganttActualColor || '#248eff'"
                        @active-change="(value) => updateStyleColor('ganttActualColor', value)"
                        @change="(value) => finishStyleColor('ganttActualColor', value)"
                    /></el-form-item>
                  </div>
                  <div class="toggle-row">
                    <span>显示任务依赖</span
                    ><el-switch
                      :model-value="selectedWidget.style.ganttShowDependencies !== false"
                      @change="(value) => { selectedWidget.style.ganttShowDependencies = value; markDirty(); }"
                    />
                  </div>
                </div>
                <div
                  v-if="selectedWidget.type === 'milestone-timeline'"
                  class="property-section"
                >
                  <div class="section-label">里程碑时间轴</div>
                  <div class="toggle-row">
                    <span>显示开始日期</span
                    ><el-switch
                      v-model="selectedWidget.style.timelineShowDates"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>显示结束日期</span
                    ><el-switch
                      v-model="selectedWidget.style.timelineShowEndDate"
                      @change="markDirty"
                    />
                  </div>
                  <div class="style-grid">
                    <el-form-item label="节点标题位置"
                      ><el-select
                        v-model="selectedWidget.style.timelineLabelPosition"
                        @change="markDirty"
                        ><el-option label="节点上方" value="above" /><el-option
                          label="节点下方"
                          value="below" /></el-select
                    ></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.timelineDateFormat')" label="日期格式"
                      ><el-select
                        v-model="selectedWidget.style.timelineDateFormat"
                        @change="markDirty"
                        ><el-option label="YYYY-MM-DD" value="YYYY-MM-DD" /><el-option
                          label="YYYY/MM/DD"
                          value="YYYY/MM/DD" /><el-option label="MM-DD（月-日）" value="MM-DD" /></el-select
                    ></el-form-item>
                    <el-form-item label="内容分布"
                      ><el-select
                        v-model="selectedWidget.style.timelineLayout"
                        @change="markDirty"
                        ><el-option label="紧凑" value="compact" /><el-option
                          label="上下展开"
                          value="spread" /></el-select
                    ></el-form-item>
                    <el-form-item class="color-property-field" label="完成颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.timelineDoneColor"
                        @active-change="(value) => updateStyleColor('timelineDoneColor', value)"
                        @change="(value) => finishStyleColor('timelineDoneColor', value)" /></el-form-item
                    ><el-form-item class="color-property-field" label="当前颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.timelineActiveColor"
                        @active-change="(value) => updateStyleColor('timelineActiveColor', value)"
                        @change="(value) => finishStyleColor('timelineActiveColor', value)"
                    /></el-form-item>
                  </div>
                  <p class="field-hint">
                    状态字段支持“已完成、进行中、未开始”，也兼容
                    done、active、pending。
                  </p>
                </div>
                <div
                  v-if="selectedWidget.type === 'current-time'"
                  class="property-section"
                >
                  <div class="section-label">时间显示</div>
                  <div class="toggle-row">
                    <span>显示时钟图标</span
                    ><el-switch
                      v-model="selectedWidget.style.clockShowIcon"
                      @change="markDirty"
                    />
                  </div>
                  <p class="field-hint">时间文字字号使用“内容样式”中的“内容字号”配置。</p>
                </div>
                <div
                  v-if="selectedWidget.type === 'button'"
                  class="property-section"
                >
                  <div class="section-label">按钮内容</div>
                  <el-form label-position="top">
                    <el-form-item label="按钮文字">
                      <el-input
                        :model-value="dashboardButtonLabel(selectedWidget.style)"
                        @focus="beginHistory"
                        @update:model-value="(value) => setStyle('text', value)"
                        @change="endHistory"
                      />
                    </el-form-item>
                  </el-form>
                  <div v-if="selectedPropertyVisible('style.buttonFill')" class="toggle-row">
                    <span>铺满组件区域</span
                    ><el-switch
                      v-model="selectedWidget.style.buttonFill"
                      @change="markDirty"
                    />
                  </div>
                  <el-form label-position="top">
                    <el-form-item v-if="selectedPropertyVisible('style.buttonShape')" label="按钮形状"
                      ><el-select
                        :model-value="selectedWidget.style.buttonShape || 'rect'"
                        @change="(value) => setStyle('buttonShape', value)"
                        ><el-option label="矩形" value="rect" /><el-option
                          label="斜角导航"
                          value="angled"
                      /></el-select
                    ></el-form-item>
                  </el-form>
                  <div class="style-grid">
                    <el-form-item label="文字左右留白"
                      ><el-input-number
                        v-model="selectedWidget.style.buttonPaddingX"
                        :min="0"
                        :max="80"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="文字上下留白"
                      ><el-input-number
                        v-model="selectedWidget.style.buttonPaddingY"
                        :min="0"
                        :max="40"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                </div>
                <div
                  v-if="selectedWidget.type === 'advanced-table'"
                  class="property-section"
                >
                  <div class="section-label">高级表格</div>
                  <el-form label-position="top">
                    <el-form-item label="单元格格式规则"
                      ><el-input
                        type="textarea"
                        :rows="5"
                        :model-value="JSON.stringify(selectedWidget.style.tableRules || [], null, 2)"
                        @change="setTableRules"
                    /></el-form-item>
                  </el-form>
                  <p class="field-hint">按字段配置 field、匹配值 equals、颜色 color、背景 background、标记 badge、后缀 suffix。省略 equals 则应用到整列。</p>
                  <div class="toggle-row">
                    <span>显示表头</span
                    ><el-switch
                      v-model="selectedWidget.style.advancedShowHeader"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>显示序号</span
                    ><el-switch
                      v-model="selectedWidget.style.advancedShowIndex"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>斑马纹</span
                    ><el-switch
                      v-model="selectedWidget.style.advancedStripe"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>自动滚动</span
                    ><el-switch
                      v-model="selectedWidget.style.advancedScroll"
                      @change="markDirty"
                    />
                  </div>
                  <el-form label-position="top" class="single-number-form"
                    ><el-form-item label="行高"
                      ><el-input-number
                        v-model="selectedWidget.style.advancedRowHeight"
                        :min="20"
                        :max="80"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="首列宽度（%）"
                      ><el-input-number
                        v-model="selectedWidget.style.tableFirstColumnWidth"
                        :min="20"
                        :max="90"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                  ></el-form>
                  <div class="style-grid">
                    <el-form-item class="color-property-field" label="表头颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.tableHeaderColor"
                        @active-change="(value) => updateStyleColor('tableHeaderColor', value)"
                        @change="(value) => finishStyleColor('tableHeaderColor', value)" /></el-form-item
                    ><el-form-item class="color-property-field" label="正文颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.tableTextColor"
                        @active-change="(value) => updateStyleColor('tableTextColor', value)"
                        @change="(value) => finishStyleColor('tableTextColor', value)" /></el-form-item
                    ><el-form-item class="color-property-field" label="末列颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.tableValueColor"
                        @active-change="(value) => updateStyleColor('tableValueColor', value)"
                        @change="(value) => finishStyleColor('tableValueColor', value)" /></el-form-item
                    ><el-form-item label="横向内边距"
                      ><el-input-number
                        v-model="selectedWidget.style.tableCellPadding"
                        :min="0"
                        :max="32"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                </div>
                <div v-if="selectedWidget.type === 'table'" class="property-section">
                  <div class="section-label">数据表格滚动</div>
                  <div class="toggle-row">
                    <span>自动滚动</span>
                    <el-switch v-model="selectedWidget.style.tableAutoScroll" @change="markDirty" />
                  </div>
                  <el-form v-if="selectedWidget.style.tableAutoScroll" label-position="top">
                    <el-form-item label="滚动速度（秒/行）">
                      <el-input-number v-model="selectedWidget.style.tableScrollSeconds" :min="1" :max="60" controls-position="right" @change="markDirty" />
                    </el-form-item>
                  </el-form>
                  <p class="field-hint">内容超出可视区域时连续向上滚动，表头固定；秒数越小越快，鼠标悬停时暂停。</p>
                </div>
                <div
                  v-if="selectedWidget.type === 'access-list'"
                  class="property-section"
                >
                  <div class="section-label">图文记录列表</div>
                  <div class="style-grid">
                    <el-form-item label="记录类型"
                      ><el-select
                        v-model="selectedWidget.style.accessVariant"
                        @change="markDirty"
                        ><el-option label="人员" value="person" /><el-option
                          label="车辆"
                          value="vehicle" /></el-select
                    ></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.accessAvatarSize')" label="头像大小"
                      ><el-input-number
                        v-model="selectedWidget.style.accessAvatarSize"
                        :min="24"
                        :max="72"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="行高"
                      ><el-input-number
                        v-model="selectedWidget.style.accessRowHeight"
                        :min="56"
                        :max="120"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div class="toggle-row">
                    <span>显示头像</span
                    ><el-switch
                      v-model="selectedWidget.style.accessShowAvatar"
                      @change="markDirty"
                    />
                  </div>
                  <div class="style-grid">
                    <el-form-item label="绿色状态文字"
                      ><el-input
                        v-model="selectedWidget.style.accessStatusSuccessText"
                        maxlength="16"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="中性状态文字"
                      ><el-input
                        v-model="selectedWidget.style.accessStatusNeutralText"
                        maxlength="16"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                </div>
                <div
                  v-if="selectedWidget.type === 'ring-text'"
                  class="property-section"
                >
                  <div class="section-label">轨道环形文字</div>
                  <div class="style-grid">
                    <el-form-item label="轨道半径"
                      ><el-input-number
                        v-model="selectedWidget.style.ringTextRadius"
                        :min="10"
                        :max="48"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="旋转速度"
                      ><el-input-number
                        v-model="selectedWidget.style.ringTextSpeed"
                        :min="0"
                        :max="120"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="倾角"
                      ><el-input-number
                        v-model="selectedWidget.style.ringTextTilt"
                        :min="-45"
                        :max="45"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div class="style-grid">
                    <el-form-item v-if="selectedPropertyVisible('style.ringTextDirection')" label="方向"
                      ><el-select
                        v-model="selectedWidget.style.ringTextDirection"
                        @change="markDirty"
                        ><el-option label="顺时针" value="normal" /><el-option
                          label="逆时针"
                          value="reverse" /></el-select></el-form-item
                    ><el-form-item label="中心文字"
                      ><el-input
                        v-model="selectedWidget.style.ringTextCenterText"
                        maxlength="32"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div class="toggle-row">
                    <span>显示轨道线</span
                    ><el-switch
                      v-model="selectedWidget.style.ringTextShowOrbit"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>渐变文字</span
                    ><el-switch
                      v-model="selectedWidget.style.ringTextGradient"
                      @change="markDirty"
                    />
                  </div>
                </div>
                <div v-if="selectedWidget.type === 'button' || selectedWidget.style.titleVisible !== false" class="property-section resource-picker-section">
                  <div class="section-label">{{ selectedWidget.type === 'button' ? '按钮切图' : '标题栏切图' }}</div>
                  <div class="toggle-row">
                    <span>{{ selectedWidget.type === 'button' ? '使用图片按钮' : '使用图片标题栏' }}</span
                    ><el-switch
                      :model-value="selectedWidget.style.titleImageEnabled"
                      @change="setTitleImageEnabled"
                    />
                  </div>
                  <template v-if="selectedWidget.style.titleImageEnabled">
                    <el-form label-position="top">
                      <el-form-item :label="selectedWidget.type === 'button' ? '按钮背景图片' : '标题栏图片'"
                        ><DesignerTreeSelect
                          :model-value="selectedWidget.style.titleImageRef"
                          :data="imagePickerTree"
                          :props="resourceTreeProps"
                          node-key="value"
                          check-strictly
                          clearable
                          filterable
                          render-after-expand
                          placeholder="从资源管理图库选择"
                          empty-text="暂无可选图片"
                          @change="setTitleImageResource"
                          />
                      </el-form-item>
                      <div class="title-image-grid">
                        <el-form-item v-if="selectedPropertyVisible('style.titleImageFit')" label="适配方式"
                          ><el-select
                            v-model="selectedWidget.style.titleImageFit"
                            @change="markDirty"
                            ><el-option
                              label="拉伸铺满"
                              value="stretch" /><el-option
                              label="完整显示"
                              value="contain" /><el-option
                              label="裁切铺满"
                              value="cover" /></el-select></el-form-item
                        ><el-form-item v-if="selectedPropertyVisible('style.titleImageAlign')" label="水平对齐"
                          ><el-select
                            v-model="selectedWidget.style.titleImageAlign"
                            @change="markDirty"
                            ><el-option label="左对齐" value="left" /><el-option
                              label="居中"
                              value="center" /><el-option
                              label="右对齐"
                              value="right" /></el-select></el-form-item
                        ><el-form-item v-if="selectedPropertyVisible('style.titleImageHeight')" label="标题栏高度"
                          ><el-input-number
                            v-model="selectedWidget.style.titleImageHeight"
                            :min="20"
                            :max="120"
                            controls-position="right"
                            @change="markDirty"
                        /></el-form-item>
                      </div>
                    </el-form>
                    <p
                      v-if="selectedWidget.style.titleImageFit !== 'contain'"
                      class="field-hint"
                    >
                      水平对齐仅在“完整显示”时有效；拉伸和裁切模式固定使用居中位置。
                    </p>
                    <p
                      v-if="
                        selectedWidget.style.titleImageRef &&
                        !isRegisteredImageResource(
                          selectedWidget.style.titleImageRef,
                        )
                      "
                      class="field-hint resource-warning"
                    >
                      当前图片不在图库中，请重新选择。
                    </p>
                    <p v-if="selectedWidget.type === 'button'" class="field-hint">
                      可选用图库中的标题栏切图作为按钮背景。图片铺满整个按钮区域，尺寸由“基础”中的宽、高控制；图片自带文字时可清空“按钮文字”。
                    </p>
                    <p v-else class="field-hint">
                      切图只作用于组件标题栏；图片自带标题文字时，可将下方标题文字留空。
                    </p>
                  </template>
                </div>
                <div v-if="selectedWidget.type !== 'button'" class="property-section">
                  <div class="section-label">{{ selectedWidget.type === 'statistics' ? '统计标题' : '组件标题栏' }}</div>
                  <el-form label-position="top"
                    ><el-form-item v-if="selectedPropertyVisible('style.title')" :label="selectedWidget.type === 'statistics' ? '手工标题' : '显示标题'"
                      ><el-input
                        v-model="selectedWidget.style.title"
                        @focus="beginHistory"
                        @change="endHistory"
                        @keydown.enter.stop.prevent="endHistory" />
                      <p v-if="selectedWidget.type === 'statistics'" class="field-hint">隐藏标题后仍可编辑。标题字段映射有值时优先显示映射内容，清空映射可使用此处文字。</p></el-form-item
                    ><el-form-item label="标题可见"
                      ><el-switch
                        v-model="selectedWidget.style.titleVisible"
                        @change="markDirty" /></el-form-item
                    ><el-form-item v-if="selectedPropertyVisible('style.subtitle')" label="副标题"
                      ><el-input
                        v-model="selectedWidget.style.subtitle"
                        placeholder="可选的补充说明"
                        @focus="beginHistory"
                        @change="endHistory"
                    /></el-form-item>
                    <div class="style-grid">
                      <el-form-item v-if="selectedPropertyVisible('style.titleAlign')" label="标题对齐"
                        ><el-select
                          v-model="selectedWidget.style.titleAlign"
                          @change="markDirty"
                          ><el-option label="左对齐" value="left" /><el-option
                            label="居中"
                            value="center" /><el-option
                            label="右对齐"
                            value="right" /></el-select></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.titleVerticalAlign')" label="标题垂直对齐"
                        ><el-select
                          v-model="selectedWidget.style.titleVerticalAlign"
                          @change="markDirty"
                          ><el-option label="顶部" value="top" /><el-option
                            label="居中"
                            value="middle" /><el-option
                            label="底部"
                            value="bottom" /></el-select></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.titleFontSize')" label="标题字号"
                        ><el-input-number
                          v-model="selectedWidget.style.titleFontSize"
                          :min="10"
                          :max="48"
                          controls-position="right"
                          @change="markDirty" /></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.titleFontWeight')" label="标题字重"
                        ><el-select
                          v-model="selectedWidget.style.titleFontWeight"
                          @change="markDirty"
                          ><el-option label="常规" :value="400" /><el-option
                            label="中等"
                            :value="500" /><el-option
                            label="加粗"
                            :value="600" /><el-option
                            label="特粗"
                            :value="700" /></el-select
                      ></el-form-item>
                    </div>
                    <div class="style-grid">
                      <el-form-item v-if="selectedPropertyVisible('style.subtitleFontSize')" label="副标题字号"
                        ><el-input-number
                          v-model="selectedWidget.style.subtitleFontSize"
                          :min="8"
                          :max="32"
                          controls-position="right"
                          @change="markDirty" /></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.subtitleFontWeight')" label="副标题字重"
                        ><el-select
                          v-model="selectedWidget.style.subtitleFontWeight"
                          @change="markDirty"
                          ><el-option label="常规" :value="400" /><el-option
                            label="中等"
                            :value="500" /><el-option
                            label="加粗"
                            :value="600" /></el-select
                      ></el-form-item>
                    </div>
                    <div class="title-padding-grid">
                      <el-form-item v-if="selectedPropertyVisible('style.titlePaddingTop')" label="上边距"
                        ><el-input-number
                          v-model="selectedWidget.style.titlePaddingTop"
                          :min="0"
                          :max="120"
                          controls-position="right"
                          @change="markDirty" /></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.titlePaddingRight')" label="右边距"
                        ><el-input-number
                          v-model="selectedWidget.style.titlePaddingRight"
                          :min="0"
                          :max="120"
                          controls-position="right"
                          @change="markDirty" /></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.titlePaddingBottom')" label="下边距"
                        ><el-input-number
                          v-model="selectedWidget.style.titlePaddingBottom"
                          :min="0"
                          :max="120"
                          controls-position="right"
                          @change="markDirty" /></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.titlePaddingLeft')" label="左边距"
                        ><el-input-number
                          v-model="selectedWidget.style.titlePaddingLeft"
                          :min="0"
                          :max="120"
                          controls-position="right"
                          @change="markDirty"
                      /></el-form-item>
                    </div>
                    <p class="field-hint">
                      标题边距使用 CSS
                      内边距控制，可精确调整标题文字在普通标题栏或切图背景中的位置。
                    </p>
                    <div class="style-grid">
                      <el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.titleColor')" label="标题颜色"
                        ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                          :model-value="selectedWidget.style.titleColor"
                          @active-change="
                            (value) => updateStyleColor('titleColor', value)
                          "
                          @change="
                            (value) => finishStyleColor('titleColor', value)
                          " /></el-form-item
                      ><el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.subtitleColor')" label="副标题颜色"
                        ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                          :model-value="selectedWidget.style.subtitleColor"
                          @active-change="
                            (value) => updateStyleColor('subtitleColor', value)
                          "
                          @change="
                            (value) => finishStyleColor('subtitleColor', value)
                          "
                      /></el-form-item>
                    </div>
                  </el-form>
                </div>
                <div v-if="['text', 'rich-text', 'video', 'iframe', 'current-time', 'weather', 'color-block', 'icon', 'metric-card', 'number-flip', 'progress', 'gauge'].includes(selectedWidget.type)" class="property-section">
                  <div class="section-label">组件内容</div>
                  <el-form label-position="top">
                    <el-form-item
                      v-if="
                        ['text', 'rich-text'].includes(
                          selectedWidget.type,
                        )
                      "
                      label="正文内容"
                      ><el-input
                        v-model="selectedWidget.style.text"
                        type="textarea"
                        :rows="4"
                        @focus="beginHistory"
                        @change="endHistory" /></el-form-item
                    ><template v-if="selectedWidget.type === 'video'"
                      ><el-form-item label="封面资源"
                        ><DesignerTreeSelect
                          :model-value="selectedWidget.style.posterRef"
                          :data="imagePickerTree"
                          :props="resourceTreeProps"
                          node-key="value"
                          check-strictly
                          clearable
                          filterable
                          render-after-expand
                          placeholder="从图片资源选择封面"
                          empty-text="暂无可选图片"
                          @change="(value) => setStyle('posterRef', value)"
                        /></el-form-item>
                      <div class="toggle-row">
                        <span>自动播放（静音）</span
                        ><el-switch
                          v-model="selectedWidget.style.autoplay"
                          @change="markDirty"
                        />
                      </div>
                      <div class="toggle-row">
                        <span>循环播放</span
                        ><el-switch
                          v-model="selectedWidget.style.loop"
                          @change="markDirty"
                        />
                      </div>
                      <p class="field-hint">
                        只允许平台资源；浏览器策略要求自动播放时保持静音。
                      </p></template
                    ><el-form-item
                      v-if="selectedWidget.type === 'iframe'"
                      label="平台内部页面"
                      ><el-input
                        v-model="selectedWidget.style.iframeRef"
                        placeholder="例如 /dashboard/runtime/1?embedMode=1"
                        @focus="beginHistory"
                        @change="endHistory"
                      />
                      <p class="field-hint">
                        仅允许 /dashboard/runtime/ 下的运行页，保留登录和页面权限校验；不允许外部地址或循环嵌入。
                      </p></el-form-item
                    ><template v-if="selectedWidget.type === 'current-time'"
                      ><el-form-item label="时间格式"
                        ><el-select
                          v-model="selectedWidget.style.timeFormat"
                          @change="markDirty"
                          ><el-option label="时:分" value="HH:mm" /><el-option
                            label="时:分:秒"
                            value="HH:mm:ss" /><el-option
                            label="日期"
                            value="YYYY-MM-DD" /><el-option
                            label="日期 时:分"
                            value="YYYY-MM-DD HH:mm" /><el-option
                            label="日期 时:分:秒"
                            value="YYYY-MM-DD HH:mm:ss" /></el-select></el-form-item></template
                    ><template v-if="selectedWidget.type === 'weather'"
                      ><el-form-item label="温度单位"
                        ><el-input
                          v-model="selectedWidget.style.temperatureUnit"
                          placeholder="℃"
                          @focus="beginHistory"
                          @change="endHistory" /></el-form-item></template
                    ><template v-if="selectedWidget.type === 'color-block'"
                      ><el-form-item class="color-property-field" label="静态颜色"
                        ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                          :model-value="selectedWidget.style.blockColor"
                          show-alpha
                          @active-change="(value) => updateStyleColor('blockColor', value)"
                          @change="(value) => finishStyleColor('blockColor', value)" /></el-form-item
                      ><el-form-item label="状态文字"
                        ><el-input
                          v-model="selectedWidget.style.blockLabel"
                          placeholder="例如 正常"
                          @focus="beginHistory"
                          @change="endHistory" /></el-form-item></template
                    ><el-form-item
                      v-if="selectedWidget.type === 'icon'"
                      label="平台图标"
                      ><el-button class="icon-picker-trigger" aria-label="选择平台图标" @click="openIconPicker">
                        <span class="icon-picker-trigger__preview"><DashboardIcon :options="{ ...selectedWidget.style, fontSize: 20, iconFrame: false }" /></span>
                        <span class="icon-picker-trigger__label" :title="iconSelectionLabel(selectedWidget)">{{ iconSelectionLabel(selectedWidget) }}</span>
                        <el-icon><Grid /></el-icon>
                      </el-button>
                      <p v-if="dashboardIconUsesImage(selectedWidget.style)" class="field-hint">图片保持原色，GIF 按原始动画播放；图标尺寸在“内容样式”中调整。</p>
                    </el-form-item
                    ><el-form-item
                      v-if="
                        [
                          'metric-card',
                          'number-flip',
                          'progress',
                          'gauge',
                        ].includes(selectedWidget.type)
                      "
                      label="单位"
                      ><el-input
                        v-model="selectedWidget.style.unit"
                        placeholder="例如 %、人、项"
                        @focus="beginHistory"
                        @change="endHistory" /></el-form-item
                  ></el-form>
                  <div v-if="selectedPropertyVisible('style.iconFrame')" class="toggle-row">
                    <span>圆形图标底框</span
                    ><el-switch
                      v-model="selectedWidget.style.iconFrame"
                      @change="markDirty"
                    />
                  </div>
                </div>
                <div
                  v-if="selectedWidget.type === 'image'"
                  class="property-section resource-picker-section"
                >
                  <div class="section-label">图库资源</div>
                  <DesignerTreeSelect
                    :model-value="selectedWidget.style.imageRef"
                    :data="imagePickerTree"
                    :props="resourceTreeProps"
                    node-key="value"
                    check-strictly
                    clearable
                    filterable
                    render-after-expand
                    placeholder="从图库选择"
                    empty-text="暂无可选图片"
                    @change="setImageResource"
                  />
                  <div class="style-grid resource-style-grid">
                    <el-form-item label="图片适配"
                      ><el-select
                        v-model="selectedWidget.style.imageFit"
                        @change="markDirty"
                        ><el-option label="完整显示" value="contain" /><el-option
                          label="铺满裁切"
                          value="cover" /><el-option
                          label="拉伸填充"
                          value="fill" /><el-option
                          label="原始尺寸"
                          value="none" /><el-option
                          label="按需缩小"
                          value="scale-down" /></el-select
                    ></el-form-item>
                    <el-form-item label="图片位置"
                      ><el-select
                        v-model="selectedWidget.style.imagePosition"
                        @change="markDirty"
                        ><el-option label="居中" value="center" /><el-option
                          label="顶部"
                          value="top" /><el-option
                          label="底部"
                          value="bottom" /><el-option
                          label="左侧"
                          value="left" /><el-option
                          label="右侧"
                          value="right" /></el-select
                    ></el-form-item>
                  </div>
                  <p
                    v-if="
                      selectedWidget.style.imageRef &&
                      !isRegisteredImageResource(selectedWidget.style.imageRef)
                    "
                    class="field-hint resource-warning"
                  >
                    当前地址不在图库中，请重新选择资源。
                  </p>
                  <p class="field-hint">
                    图片只能从“资源管理”的图库中选择；需要新增或替换时，请先到资源管理登记。
                  </p>
                </div>
                <div
                  v-if="selectedWidget.type === 'video'"
                  class="property-section resource-picker-section"
                >
                  <div class="section-label">视频资源候选</div>
                  <DesignerTreeSelect
                    :model-value="selectedWidget.style.videoRef"
                    :data="videoPickerTree"
                    :props="resourceTreeProps"
                    node-key="value"
                    check-strictly
                    clearable
                    filterable
                    render-after-expand
                    placeholder="从视频资源选择"
                    empty-text="暂无可选视频"
                    @change="(value) => setStyle('videoRef', value)"
                  />
                  <p class="field-hint">
                    视频资源来自“资源管理”，仅允许平台托管文件。
                  </p>
                </div>
                <div
                  v-if="selectedWidget.type === 'custom-html'"
                  class="property-section custom-html-editor-section"
                >
                  <div class="section-label section-label-inline">
                    <span>自定义内容</span
                    ><el-tag type="warning" effect="plain">隔离沙箱</el-tag>
                  </div>
                  <el-input
                    v-model="selectedWidget.style.htmlContent"
                    type="textarea"
                    :rows="16"
                    spellcheck="false"
                    maxlength="262144"
                    show-word-limit
                    placeholder="输入网页内容；运行时在隔离沙箱中执行"
                    @focus="beginHistory"
                    @change="endHistory"
                  />
                  <p class="field-hint">
                    支持内联网页样式、脚本和联动；禁止嵌套页面、外部网络地址、事件属性及
                    Cookie/存储访问。保存或发布时由服务端再次校验。
                  </p>
                </div>
                <div
                  v-if="selectedWidget.type === 'carousel-table'"
                  class="property-section"
                >
                  <div class="section-label">轮播</div>
                  <label class="range-field"
                    >切换间隔
                    <span
                      >{{ selectedWidget.style.carouselSeconds || 4 }} 秒</span
                    ><el-slider
                      :model-value="
                        Number(selectedWidget.style.carouselSeconds) || 4
                      "
                      :min="2"
                      :max="60"
                      :step="1"
                      @update:model-value="
                        (value) =>
                          updateStyleSlider('carouselSeconds', Number(value))
                      "
                      @change="finishSliderChange"
                  /></label>
                  <p class="field-hint">运行态按此间隔轮换数据行。</p>
                </div>
                <div
                  v-if="selectedWidget.type === 'tabs'"
                  class="property-section"
                >
                  <div class="section-label section-label-inline">
                    <span>选项卡</span
                    ><el-button text type="primary" @click="addTab"
                      >+ 添加</el-button
                    >
                  </div>
                  <div
                    v-for="(tab, index) in selectedWidget.style.tabs"
                    :key="tab.key || index"
                    class="tab-editor-item"
                  >
                    <div class="tab-editor-head">
                      <span>选项 {{ index + 1 }}</span
                      ><el-button
                        text
                        type="danger"
                        :disabled="selectedWidget.style.tabs.length <= 1"
                        @click="removeTab(index)"
                        ><el-icon><Close /></el-icon
                      ></el-button>
                    </div>
                    <el-input
                      v-model="tab.label"
                      placeholder="标题"
                      @focus="beginHistory"
                      @change="syncTabsJsonAndHistory"
                    />
                    <el-input
                      v-model="tab.content"
                      type="textarea"
                      :rows="2"
                      placeholder="纯文本内容"
                      @focus="beginHistory"
                      @change="syncTabsJsonAndHistory"
                    />
                    <el-select
                      v-model="tab.widgetIds"
                      multiple
                      clearable
                      collapse-tags
                      collapse-tags-tooltip
                      placeholder="绑定画布组件（可选）"
                      @change="syncTabsJsonAndHistory"
                    >
                      <el-option
                        v-for="target in schema.widgets.filter(
                          (item) => item.id !== selectedWidget.id,
                        )"
                        :key="target.id"
                        :label="
                          target.name ||
                          target.style?.title ||
                          meta(target.type).label
                        "
                        :value="target.id"
                      />
                    </el-select>
                  </div>
                  <el-collapse class="advanced-tab-fields"
                    ><el-collapse-item title="高级 JSON 配置" name="json"
                      ><el-input
                        v-model="selectedWidget.style.tabsJson"
                        type="textarea"
                        :rows="6"
                        spellcheck="false"
                        placeholder='[{"label":"概览","content":"概览内容","widgetIds":[]}]'
                        @focus="beginHistory"
                        @change="applyTabsJson" /></el-collapse-item
                  ></el-collapse>
                  <p class="field-hint">
                    可视化配置标题、纯文本内容和画布组件归属；发布时仍只保存白名单字段，不执行
                    HTML 或脚本。
                  </p>
                </div>
                <div
                  v-if="
                    ['filter-form', 'designer-form', 'online-form'].includes(
                      selectedWidget.type,
                    )
                  "
                  class="property-section"
                >
                  <div class="section-label section-label-inline">
                    <span>查询字段</span
                    ><el-button text type="primary" @click="addFormField"
                      >+ 添加字段</el-button
                    >
                  </div>
                  <div
                    v-for="(field, index) in selectedWidget.style.formFields"
                    :key="field.name || index"
                    class="form-field-editor"
                  >
                    <el-input
                      v-model="field.label"
                      placeholder="显示名称"
                      @change="markDirty"
                    /><el-input
                      v-model="field.name"
                      placeholder="字段名"
                      @change="markDirty"
                    /><el-select
                      v-model="field.type"
                      placeholder="控件类型"
                      @change="markDirty"
                      ><el-option
                        v-for="item in formFieldTypes"
                        :key="item.value"
                        :label="item.label"
                        :value="item.value" /></el-select
                    ><el-select
                      v-model="field.parameter"
                      clearable
                      filterable
                      placeholder="页面参数"
                      @change="markDirty"
                      ><el-option
                        v-for="filter in schema.filters"
                        :key="filter.id"
                        :label="filter.label || filter.parameter"
                        :value="filter.parameter" /></el-select
                    ><el-input
                      v-if="
                        ['SELECT', 'RADIO'].includes(
                          String(field.type || '').toUpperCase(),
                        )
                      "
                      v-model="field.optionsJson"
                      placeholder='选项 JSON，例如 [{"label":"全部","value":""}]'
                      @change="applyFormFieldOptions(field)"
                    /><el-button
                      text
                      type="danger"
                      :disabled="selectedWidget.style.formFields.length <= 1"
                      @click="removeFormField(index)"
                      ><el-icon><Close /></el-icon
                    ></el-button>
                  </div>
                  <el-collapse class="advanced-form-fields"
                    ><el-collapse-item title="高级 JSON 配置" name="json"
                      ><el-input
                        v-model="selectedWidget.style.formFieldsJson"
                        type="textarea"
                        :rows="6"
                        spellcheck="false"
                        placeholder='[{"name":"keyword","label":"关键词","parameter":"keyword"}]'
                        @focus="beginHistory"
                        @change="applyFormFieldsJson" /></el-collapse-item
                  ></el-collapse>
                  <p class="field-hint">
                    表单字段通过页面参数联动数据组件；可选文本、数字、日期、日期时间和下拉选项。parameter
                    必须对应页面过滤器，在线表单仅用于查询联动，不写回业务数据。
                  </p>
                </div>
                <div v-if="selectedWidget.type !== 'button' || !selectedWidget.style.titleImageEnabled" class="property-section">
                  <div class="section-label">背景融合</div>
                  <div v-if="selectedPropertyVisible('style.backgroundTransparent')" class="toggle-row">
                    <span>{{ selectedWidget.type === 'button' ? '按钮背景透明' : '容器背景透明' }}</span
                    ><el-switch
                      v-model="selectedWidget.style.backgroundTransparent"
                      @change="markDirty"
                    />
                  </div>
                  <div class="toggle-row">
                    <span>嵌入背景模式</span
                    ><el-switch
                      v-model="selectedWidget.style.embeddedMode"
                      @change="markDirty"
                    />
                  </div>
                  <div v-if="selectedPropertyVisible('style.qualityVisible')" class="toggle-row">
                    <span>显示数据质量标识</span
                    ><el-switch
                      v-model="selectedWidget.style.qualityVisible"
                      @change="markDirty"
                    />
                  </div>
                  <p class="field-hint">
                    背景透明可单独使用；嵌入背景还会去除容器边框、阴影和内部卡片底色，文字与数据保持清晰。
                  </p>
                </div>
                <div v-if="selectedCapabilities.typography || selectedCapabilities.accent" class="property-section">
                  <div class="section-label">内容样式</div>
                  <div v-if="selectedPropertyVisible('style.useSystemPalette')" class="toggle-row">
                    <span>使用系统配色</span
                    ><el-switch
                      :model-value="
                        selectedWidget.style.useSystemPalette !== false
                      "
                      @change="(value) => setStyle('useSystemPalette', value)"
                    />
                  </div>
                  <div v-if="selectedPropertyVisible('style.color')" class="color-row">
                    <label>内容主色</label
                    ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                      :model-value="selectedWidget.style.color"
                      @active-change="
                        (value) => updateStyleColor('color', value)
                      "
                      @change="(value) => finishStyleColor('color', value)"
                    />
                  </div>
                  <el-form label-position="top">
                  <div class="typography-grid">
                    <el-form-item v-if="selectedPropertyVisible('style.fontSize')" :label="selectedWidget.type === 'icon' ? '图标尺寸' : '内容字号'"
                      ><el-input-number
                        :model-value="selectedWidget.style.fontSize || 16"
                        :min="10"
                        :max="96"
                        controls-position="right"
                        @update:model-value="
                          (value) =>
                            updateStyleSlider('fontSize', Number(value))
                        "
                        @change="finishSliderChange"
                    /></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.fontWeight')" label="内容字重"
                      ><el-select
                        :model-value="
                          String(selectedWidget.style.fontWeight || 400)
                        "
                        @change="
                          (value) => setStyle('fontWeight', Number(value))
                        "
                        ><el-option label="常规" value="400" /><el-option
                          label="中等"
                          value="500" /><el-option
                          label="加粗"
                          value="700" /></el-select
                    ></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.textAlign')" label="内容水平对齐"
                      ><el-select
                        v-model="selectedWidget.style.textAlign"
                        @change="markDirty"
                        ><el-option label="左对齐" value="left" /><el-option
                          label="居中"
                          value="center" /><el-option
                          label="右对齐"
                          value="right" /></el-select
                    ></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.contentVerticalAlign')" label="内容垂直对齐"
                      ><el-select
                        v-model="selectedWidget.style.contentVerticalAlign"
                        @change="markDirty"
                        ><el-option label="顶部" value="top" /><el-option
                          label="居中"
                          value="center" /><el-option
                          label="底部"
                          value="bottom" /></el-select
                    ></el-form-item>
                  </div>
                  </el-form>
                </div>
                <div class="property-section">
                  <div class="section-label">{{ selectedWidget.type === 'button' ? '按钮外观' : '容器外观' }}</div>
                  <div v-if="selectedPropertyVisible('style.backgroundColor')" class="color-row">
                    <label>背景</label
                    ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                      :model-value="selectedWidget.style.backgroundColor"
                      show-alpha
                      @active-change="
                        (value) => updateStyleColor('backgroundColor', value)
                      "
                      @change="
                        (value) => finishStyleColor('backgroundColor', value)
                      "
                    />
                  </div>
                  <label class="range-field"
                    >整体透明度
                    <span
                      >{{
                        Math.round((selectedWidget.style.opacity ?? 1) * 100)
                      }}%</span
                    ><el-slider
                      :model-value="(selectedWidget.style.opacity ?? 1) * 100"
                      :min="0"
                      :max="100"
                      @update:model-value="
                        (value) =>
                          updateStyleSlider('opacity', Number(value) / 100)
                      "
                      @change="finishSliderChange"
                  /></label>
                  <label v-if="selectedPropertyVisible('style.borderRadius')" class="range-field"
                    >圆角
                    <span>{{ selectedWidget.style.borderRadius || 0 }}px</span
                    ><el-slider
                      :model-value="selectedWidget.style.borderRadius || 0"
                      :min="0"
                      :max="32"
                      @update:model-value="
                        (value) =>
                          updateStyleSlider('borderRadius', Number(value))
                      "
                      @change="finishSliderChange"
                  /></label>
                  <div v-if="selectedPropertyVisible('style.contentPaddingTop')" class="section-label sub-section-label">{{ selectedWidget.type === 'button' ? '按钮外侧留白' : '内容内边距' }}</div>
                  <div v-if="selectedPropertyVisible('style.contentPaddingTop')" class="legend-margin-grid">
                    <el-form-item label="上"
                      ><el-input-number
                        v-model="selectedWidget.style.contentPaddingTop"
                        :min="0"
                        :max="120"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="右"
                      ><el-input-number
                        v-model="selectedWidget.style.contentPaddingRight"
                        :min="0"
                        :max="120"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="下"
                      ><el-input-number
                        v-model="selectedWidget.style.contentPaddingBottom"
                        :min="0"
                        :max="120"
                        controls-position="right"
                        @change="markDirty" /></el-form-item
                    ><el-form-item label="左"
                      ><el-input-number
                        v-model="selectedWidget.style.contentPaddingLeft"
                        :min="0"
                        :max="120"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                </div>
                <div v-if="selectedWidget.type !== 'button' || !selectedWidget.style.titleImageEnabled" class="property-section">
                  <div class="section-label">边框</div>
                  <div v-if="selectedPropertyVisible('style.borderTransparent')" class="toggle-row border-transparent-row">
                    <span>{{ selectedWidget.type === 'button' ? '按钮边框透明' : '容器边框透明' }}</span
                    ><el-switch
                      v-model="selectedWidget.style.borderTransparent"
                      @change="markDirty"
                    />
                  </div>
                  <div v-if="selectedPropertyVisible('style.borderColor')" class="color-row">
                    <label>颜色</label
                    ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                      :model-value="selectedWidget.style.borderColor"
                      @active-change="
                        (value) => updateStyleColor('borderColor', value)
                      "
                      @change="
                        (value) => finishStyleColor('borderColor', value)
                      "
                    />
                  </div>
                  <div class="style-grid border-style-grid">
                    <el-form-item v-if="selectedPropertyVisible('style.borderStyle')" label="线条样式"
                      ><el-select
                        v-model="selectedWidget.style.borderStyle"
                        @change="markDirty"
                        ><el-option label="实线" value="solid" /><el-option
                          label="虚线"
                          value="dashed" /><el-option
                          label="点线"
                          value="dotted" /><el-option
                          label="双线"
                          value="double" /></el-select
                    ></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.borderWidth')" label="边框宽度"
                      ><el-input-number
                        :model-value="selectedWidget.style.borderWidth || 0"
                        :min="0"
                        :max="16"
                        controls-position="right"
                        @focus="beginHistory"
                        @change="(value) => setStyle('borderWidth', value)"
                    /></el-form-item>
                  </div>
                  <label v-if="selectedPropertyVisible('style.borderOpacity')" class="range-field"
                    >边框透明度
                    <span
                      >{{
                        Math.round(
                          (selectedWidget.style.borderOpacity ?? 1) * 100,
                        )
                      }}%</span
                    ><el-slider
                      :model-value="
                        (selectedWidget.style.borderOpacity ?? 1) * 100
                      "
                      :min="0"
                      :max="100"
                      @update:model-value="
                        (value) =>
                          updateStyleSlider(
                            'borderOpacity',
                            Number(value) / 100,
                          )
                      "
                      @change="finishSliderChange"
                  /></label>
                  <p class="field-hint">
                    容器边框透明会完全隐藏边框；透明度用于保留边框形态并减弱视觉强度。按钮组件的边框配置应用于按钮本体。
                  </p>
                </div>
                <div v-if="selectedWidget.type === 'word-cloud'" class="property-section">
                  <div class="section-label">文字云配色</div>
                  <el-form label-position="top"><el-form-item label="自定义配色（逗号分隔）">
                    <el-input v-model="selectedWidget.style.chartConfig.colorsText" placeholder="留空跟随页面配色" @focus="beginHistory" @change="applyChartColors" />
                  </el-form-item></el-form>
                </div>
                <div
                  v-if="selectedCapabilities.chart"
                  class="property-section"
                >
                  <div class="section-label">通用图表配置</div>
                  <div class="toggle-row">
                    <span>提示框</span
                    ><el-switch
                      v-model="selectedWidget.style.chartConfig.tooltip.show"
                      @change="markDirty"
                    />
                  </div>
                  <div
                    v-if="selectedWidget.style.chartConfig.tooltip.show"
                    class="style-grid"
                  >
                    <el-form-item label="提示字号"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.tooltip.fontSize
                        "
                        :min="10"
                        :max="32"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item class="color-property-field" label="提示文字颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.chartConfig.tooltip.textColor"
                        @active-change="(value) => updateStyleColor('chartConfig.tooltip.textColor', value)"
                        @change="(value) => finishStyleColor('chartConfig.tooltip.textColor', value)"
                    /></el-form-item>
                  </div>
                  <div v-if="selectedCapabilities.legend" class="toggle-row">
                    <span>图例</span
                    ><el-switch
                      v-model="selectedWidget.style.chartConfig.legend.show"
                      @change="markDirty"
                    />
                  </div>
                  <div
                    v-if="selectedCapabilities.legend && selectedWidget.style.chartConfig.legend.show"
                    class="style-grid"
                  >
                    <el-form-item v-if="selectedPropertyVisible('style.chartConfig.legend.position')" label="图例位置"
                      ><el-select
                        v-model="
                          selectedWidget.style.chartConfig.legend.position
                        "
                        @change="markDirty"
                        ><el-option label="顶部" value="top" /><el-option
                          label="底部"
                          value="bottom" /><el-option
                          label="左侧"
                          value="left" /><el-option
                          label="右侧"
                          value="right" /></el-select
                    ></el-form-item>
                    <el-form-item label="图例方向"
                      ><el-select
                        v-model="selectedWidget.style.chartConfig.legend.orient"
                        @change="markDirty"
                        ><el-option label="横向" value="horizontal" /><el-option
                          label="纵向"
                          value="vertical" /></el-select
                    ></el-form-item>
                    <el-form-item label="图例字号"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.legend.fontSize
                        "
                        :min="9"
                        :max="28"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item class="color-property-field" label="图例文字颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.chartConfig.legend.textColor"
                        @active-change="(value) => updateStyleColor('chartConfig.legend.textColor', value)"
                        @change="(value) => finishStyleColor('chartConfig.legend.textColor', value)"
                    /></el-form-item>
                  </div>
                  <div
                    v-if="selectedCapabilities.legend && selectedWidget.style.chartConfig.legend.show"
                    class="legend-margin-grid"
                  >
                    <el-form-item label="左边距"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.legend.margin.left
                        "
                        :min="0"
                        :max="240"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="右边距"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.legend.margin.right
                        "
                        :min="0"
                        :max="240"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="上边距"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.legend.margin.top
                        "
                        :min="0"
                        :max="240"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="下边距"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.legend.margin.bottom
                        "
                        :min="0"
                        :max="240"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <template v-if="selectedCapabilities.effectiveType === 'ring-chart'">
                    <div class="style-grid">
                      <el-form-item label="内半径（%）"
                        ><el-input-number
                          v-model="selectedWidget.style.ringInnerRadius"
                          :min="0"
                          :max="90"
                          controls-position="right"
                          @change="markDirty" /></el-form-item
                      ><el-form-item label="外半径（%）"
                        ><el-input-number
                          v-model="selectedWidget.style.ringOuterRadius"
                          :min="1"
                          :max="100"
                          controls-position="right"
                          @change="markDirty" /></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.ringLegendColumns')" label="图例列数"
                        ><el-input-number
                          v-model="selectedWidget.style.ringLegendColumns"
                          :min="1"
                          :max="4"
                          controls-position="right"
                          @change="markDirty" /></el-form-item
                      ><el-form-item v-if="selectedPropertyVisible('style.ringLegendWidth')" label="图例宽度"
                        ><el-input-number
                          v-model="selectedWidget.style.ringLegendWidth"
                          :min="80"
                          :max="600"
                          controls-position="right"
                          @change="markDirty"
                      /></el-form-item>
                      <el-form-item v-if="selectedPropertyVisible('style.ringLegendLeft')" label="图例横向位置（%）"
                        ><el-input-number
                          v-model="selectedWidget.style.ringLegendLeft"
                          :min="0"
                          :max="100"
                          controls-position="right"
                          @change="markDirty"
                      /></el-form-item>
                      <el-form-item v-if="selectedPropertyVisible('style.ringLegendItemGap')" label="图例项间距"
                        ><el-input-number
                          v-model="selectedWidget.style.ringLegendItemGap"
                          :min="0"
                          :max="40"
                          controls-position="right"
                          @change="markDirty"
                      /></el-form-item>
                    </div>
                    <div v-if="selectedPropertyVisible('style.ringLegendShowValue')" class="toggle-row">
                      <span>图例显示数值</span
                      ><el-switch
                        v-model="selectedWidget.style.ringLegendShowValue"
                        @change="markDirty"
                      />
                    </div>
                    <div class="toggle-row">
                      <span>扇区等分</span
                      ><el-switch
                        v-model="selectedWidget.style.ringEqualSegments"
                        @change="markDirty"
                      />
                    </div>
                    <el-form label-position="top">
                      <el-form-item v-if="selectedPropertyVisible('style.ringLegendSuffix')" label="图例数值后缀"
                        ><el-input
                          v-model="selectedWidget.style.ringLegendSuffix"
                          maxlength="16"
                          placeholder="例如 %、人"
                          @change="markDirty"
                      /></el-form-item>
                    </el-form>
                  </template>
                  <div
                    v-if="selectedCapabilities.axis"
                    class="style-grid"
                  >
                    <el-form-item label="横轴"
                      ><el-switch
                        v-model="selectedWidget.style.chartConfig.xAxis.show"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="纵轴"
                      ><el-switch
                        v-model="selectedWidget.style.chartConfig.yAxis.show"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="横轴类型"
                      ><el-select
                        v-model="selectedWidget.style.chartConfig.xAxis.type"
                        @change="markDirty"
                        ><el-option label="类目" value="category" /><el-option
                          label="数值"
                          value="value" /><el-option
                          label="时间"
                          value="time" /></el-select
                    ></el-form-item>
                    <el-form-item label="纵轴类型"
                      ><el-select
                        v-model="selectedWidget.style.chartConfig.yAxis.type"
                        @change="markDirty"
                        ><el-option label="数值" value="value" /><el-option
                          label="类目"
                          value="category" /><el-option
                          label="对数"
                          value="log" /></el-select
                    ></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.chartConfig.xAxis.axisLineShow')" label="横轴轴线"
                      ><el-switch
                        v-model="
                          selectedWidget.style.chartConfig.xAxis.axisLineShow
                        "
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.chartConfig.yAxis.axisLineShow')" label="纵轴轴线"
                      ><el-switch
                        v-model="
                          selectedWidget.style.chartConfig.yAxis.axisLineShow
                        "
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.chartConfig.xAxis.name')" label="横轴名称"
                      ><el-input
                        v-model="selectedWidget.style.chartConfig.xAxis.name"
                        maxlength="64"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.chartConfig.yAxis.name')" label="纵轴名称"
                      ><el-input
                        v-model="selectedWidget.style.chartConfig.yAxis.name"
                        maxlength="64"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.chartConfig.xAxis.labelRotate')" label="横轴标签旋转"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.xAxis.labelRotate
                        "
                        :min="-90"
                        :max="90"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="纵轴最小值"
                      ><el-input-number
                        v-model="selectedWidget.style.chartConfig.yAxis.min"
                        :min="-1000000000"
                        :max="1000000000"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="纵轴最大值"
                      ><el-input-number
                        v-model="selectedWidget.style.chartConfig.yAxis.max"
                        :min="-1000000000"
                        :max="1000000000"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item class="color-property-field" v-if="selectedWidget.style.chartConfig.xAxis.show && selectedWidget.style.chartConfig.xAxis.axisLineShow" label="横轴轴线颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory" :model-value="selectedWidget.style.chartConfig.xAxis.axisLineColor" @active-change="(value) => updateStyleColor('chartConfig.xAxis.axisLineColor', value)"
 @change="(value) => finishStyleColor('chartConfig.xAxis.axisLineColor', value)" /></el-form-item>
                    <el-form-item class="color-property-field" v-if="selectedWidget.style.chartConfig.yAxis.show && selectedWidget.style.chartConfig.yAxis.axisLineShow" label="纵轴轴线颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.chartConfig.yAxis.axisLineColor"
                        @active-change="(value) => updateStyleColor('chartConfig.yAxis.axisLineColor', value)"
                        @change="(value) => finishStyleColor('chartConfig.yAxis.axisLineColor', value)"
                    /></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.chartConfig.xAxis.labelFontSize')" label="横轴标签字号"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.xAxis.labelFontSize
                        "
                        :min="9"
                        :max="28"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item v-if="selectedPropertyVisible('style.chartConfig.yAxis.labelFontSize')" label="纵轴标签字号"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.yAxis.labelFontSize
                        "
                        :min="9"
                        :max="28"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.chartConfig.xAxis.labelColor')" label="横轴标签颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.chartConfig.xAxis.labelColor"
                        @active-change="(value) => updateStyleColor('chartConfig.xAxis.labelColor', value)"
                        @change="(value) => finishStyleColor('chartConfig.xAxis.labelColor', value)"
                    /></el-form-item>
                    <el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.chartConfig.yAxis.labelColor')" label="纵轴标签颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.chartConfig.yAxis.labelColor"
                        @active-change="(value) => updateStyleColor('chartConfig.yAxis.labelColor', value)"
                        @change="(value) => finishStyleColor('chartConfig.yAxis.labelColor', value)"
                    /></el-form-item>
                    <el-form-item label="横轴网格线"
                      ><el-switch v-model="selectedWidget.style.chartConfig.xAxis.splitLineShow" @change="markDirty" /></el-form-item>
                    <el-form-item label="纵轴网格线"
                      ><el-switch v-model="selectedWidget.style.chartConfig.yAxis.splitLineShow" @change="markDirty" /></el-form-item>
                    <el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.chartConfig.xAxis.splitLineColor')" label="横轴网格线颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.chartConfig.xAxis.splitLineColor"
                        @active-change="(value) => updateStyleColor('chartConfig.xAxis.splitLineColor', value)"
                        @change="(value) => finishStyleColor('chartConfig.xAxis.splitLineColor', value)"
                    /></el-form-item>
                    <el-form-item class="color-property-field" v-if="selectedPropertyVisible('style.chartConfig.yAxis.splitLineColor')" label="纵轴网格线颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.chartConfig.yAxis.splitLineColor"
                        @active-change="(value) => updateStyleColor('chartConfig.yAxis.splitLineColor', value)"
                        @change="(value) => finishStyleColor('chartConfig.yAxis.splitLineColor', value)"
                    /></el-form-item>
                  </div>
                  <div
                    v-if="selectedCapabilities.grid"
                    class="style-grid chart-grid-fields"
                  >
                    <el-form-item label="左边距"
                      ><el-input-number
                        v-model="selectedWidget.style.chartConfig.grid.left"
                        :min="0"
                        :max="240"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="右边距"
                      ><el-input-number
                        v-model="selectedWidget.style.chartConfig.grid.right"
                        :min="0"
                        :max="240"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="上边距"
                      ><el-input-number
                        v-model="selectedWidget.style.chartConfig.grid.top"
                        :min="0"
                        :max="240"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="下边距"
                      ><el-input-number
                        v-model="selectedWidget.style.chartConfig.grid.bottom"
                        :min="0"
                        :max="240"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div
                    v-if="
                      selectedCapabilities.smooth
                    "
                    class="toggle-row"
                  >
                    <span>曲线平滑</span
                    ><el-switch
                      v-model="selectedWidget.style.chartConfig.smooth"
                      @change="markDirty"
                    />
                  </div>
                  <div
                    v-if="selectedCapabilities.stack"
                    class="style-grid"
                  >
                    <el-form-item v-if="selectedCapabilities.effectiveType === 'area-chart'" label="面积透明度"
                      ><el-slider
                        :model-value="
                          selectedWidget.style.chartConfig.areaOpacity
                        "
                        :min="0"
                        :max="1"
                        :step="0.05"
                        @update:model-value="updateChartAreaOpacity"
                        @change="finishSliderChange"
                    /></el-form-item>
                    <el-form-item label="堆叠显示"
                      ><el-switch
                        v-model="selectedWidget.style.chartConfig.stack"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div
                    v-if="selectedWidget.type === 'bar3d-chart'"
                    class="style-grid"
                  >
                    <el-form-item label="立体深度"
                      ><el-input-number
                        v-model="selectedWidget.style.bar3dDepth"
                        :min="3"
                        :max="14"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div class="toggle-row">
                    <span>显示数值标签</span
                    ><el-switch
                      v-model="selectedWidget.style.chartConfig.label.show"
                      @change="markDirty"
                    />
                  </div>
                  <div
                    v-if="selectedWidget.style.chartConfig.label.show"
                    class="style-grid"
                  >
                    <el-form-item label="标签格式"
                      ><el-input
                        v-model="selectedWidget.style.chartConfig.label.format"
                        maxlength="64"
                        placeholder="例如 {b}: {c}"
                        @focus="beginHistory"
                        @change="endHistory"
                    /></el-form-item>
                    <el-form-item label="标签字号"
                      ><el-input-number
                        v-model="
                          selectedWidget.style.chartConfig.label.fontSize
                        "
                        :min="9"
                        :max="32"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="标签字重"
                      ><el-select
                        v-model="
                          selectedWidget.style.chartConfig.label.fontWeight
                        "
                        @change="markDirty"
                        ><el-option label="常规" :value="400" /><el-option
                          label="中等"
                          :value="500" /><el-option
                          label="加粗"
                          :value="700" /></el-select
                    ></el-form-item>
                    <el-form-item class="color-property-field" label="标签颜色"
                      ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                        :model-value="selectedWidget.style.chartConfig.label.color"
                        @active-change="(value) => updateStyleColor('chartConfig.label.color', value)"
                        @change="(value) => finishStyleColor('chartConfig.label.color', value)"
                    /></el-form-item>
                  </div>

                  <div
                    v-if="selectedCapabilities.center"
                    class="style-grid"
                  >
                    <el-form-item label="中心横向位置"
                      ><el-input-number
                        v-model="selectedWidget.style.chartConfig.center.x"
                        :min="0"
                        :max="100"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                    <el-form-item label="中心纵向位置"
                      ><el-input-number
                        v-model="selectedWidget.style.chartConfig.center.y"
                        :min="0"
                        :max="100"
                        controls-position="right"
                        @change="markDirty"
                    /></el-form-item>
                  </div>
                  <div
                    v-if="selectedCapabilities.markLine"
                    class="chart-auxiliary-line"
                  >
                    <div class="toggle-row">
                      <span>Y 轴辅助线</span
                      ><el-switch
                        v-model="selectedWidget.style.chartConfig.markLine.show"
                        @change="markDirty"
                      />
                    </div>
                    <div
                      v-if="selectedWidget.style.chartConfig.markLine.show"
                      class="style-grid"
                    >
                      <el-form-item label="数值"
                        ><el-input-number
                          v-model="
                            selectedWidget.style.chartConfig.markLine.value
                          "
                          :min="-1000000000"
                          :max="1000000000"
                          controls-position="right"
                          @change="markDirty"
                      /></el-form-item>
                      <el-form-item label="线型"
                        ><el-select
                          v-model="
                            selectedWidget.style.chartConfig.markLine.lineType
                          "
                          @change="markDirty"
                          ><el-option label="虚线" value="dashed" /><el-option
                            label="实线"
                            value="solid" /><el-option
                            label="点线"
                            value="dotted" /></el-select
                      ></el-form-item>
                      <el-form-item label="说明"
                        ><el-input
                          v-model="
                            selectedWidget.style.chartConfig.markLine.label
                          "
                          maxlength="64"
                          placeholder="可选"
                          @focus="beginHistory"
                          @change="endHistory"
                      /></el-form-item>
                      <el-form-item class="color-property-field" label="颜色"
                        ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                          :model-value="selectedWidget.style.chartConfig.markLine.color"
                          color-format="hex"
                          @active-change="(value) => updateStyleColor('chartConfig.markLine.color', value)"
                          @change="(value) => finishStyleColor('chartConfig.markLine.color', value)"
                      /></el-form-item>
                    </div>
                  </div>
                  <el-form label-position="top">
                    <el-form-item label="自定义配色（逗号分隔）"
                      ><el-input
                        v-model="selectedWidget.style.chartConfig.colorsText"
                        placeholder="#35d4b0,#5b8ff9"
                        @focus="beginHistory"
                        @change="applyChartColors"
                    /></el-form-item>
                  </el-form>
                  <p class="field-hint">
                    图表类型和数据系列由平台生成；这里只开放常用安全属性，不接受函数、脚本或自定义
                    series。
                  </p>
                </div>
                <div
                  v-if="selectedCapabilities.chart"
                  class="property-section"
                >
                  <div class="section-label section-label-inline">
                    <span>图表配置</span><el-tag effect="plain">白名单</el-tag>
                  </div>
                  <el-form label-position="top">
                    <el-form-item
                      v-if="selectedWidget.type === 'custom-chart'"
                      label="通用图表类型"
                    >
                      <el-select
                        v-model="selectedWidget.style.customChartType"
                        @change="markDirty"
                      >
                        <el-option label="折线图" value="line-chart" /><el-option
                          label="柱状图"
                          value="bar-chart"
                        /><el-option label="面积图" value="area-chart" />
                        <el-option label="饼图" value="pie-chart" /><el-option
                          label="环形图"
                          value="ring-chart"
                        /><el-option label="仪表盘" value="gauge" />
                        <el-option label="进度图" value="progress" /><el-option
                          label="漏斗图"
                          value="funnel"
                        /><el-option label="雷达图" value="radar" /><el-option
                          label="散点图"
                          value="scatter"
                        />
                      </el-select>
                      <p class="field-hint">
                        通用图表仍通过字段映射取数；类型和 option
                        只允许平台白名单。
                      </p>
                    </el-form-item>
                  </el-form>
                  <el-input
                    v-model="selectedWidget.style.optionJson"
                    type="textarea"
                    :rows="8"
                    spellcheck="false"
                    placeholder="输入 JSON 配置项（禁止函数和脚本）"
                    @focus="beginHistory"
                    @change="endHistory"
                  />
                  <p
                    v-if="selectedWidget.type === 'custom-chart'"
                    class="field-hint"
                  >
                    可配置 title、legend、tooltip、grid、坐标轴、颜色和受控
                    series 样式；禁止函数、HTML、外部 URL 和事件脚本。
                  </p>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane v-if="selectedCapabilities.interaction" label="交互" name="interaction">
              <div class="inspector-scroll">
                <div class="property-section">
                  <div class="section-label">点击行为</div>
                  <el-form label-position="top"
                    ><el-form-item label="动作"
                      ><el-select
                        v-model="selectedWidget.interaction.onClick"
                        @change="changeInteractionAction"
                        ><el-option label="无" value="none" /><el-option v-if="selectedCapabilities.dataInteraction"
                          label="详情弹框"
                          value="popup" /><el-option v-if="selectedCapabilities.dataInteraction"
                          label="查看明细"
                          value="drilldown" /><el-option v-if="selectedCapabilities.dataInteraction && selectedDataset && selectedDataset.dataType !== 'WEBSOCKET'"
                          label="组件逐级钻取"
                          value="self-drilldown" /><el-option v-if="selectedCapabilities.dataInteraction"
                          label="联动过滤"
                          value="filter" /><el-option
                          label="打开平台页面"
                          value="page" /><el-option
                          label="打开浏览器链接"
                          value="browser" /></el-select></el-form-item
                    ><el-form-item
                      v-if="
                        ['page', 'drilldown'].includes(
                          selectedWidget.interaction.onClick,
                        )
                      "
                      label="跳转目标类型"
                      ><el-select
                        v-model="selectedWidget.interaction.targetMode"
                        @change="changeInteractionTargetMode"
                        ><el-option
                          label="已发布页面"
                          value="published" /><el-option
                          label="站内路由（兼容旧配置）"
                          value="route" /></el-select></el-form-item
                    ><el-form-item
                      v-if="
                        ['page', 'drilldown'].includes(
                          selectedWidget.interaction.onClick,
                        ) &&
                        selectedWidget.interaction.targetMode === 'published'
                      "
                      label="已发布页面"
                      ><el-select
                        v-model="selectedWidget.interaction.targetPageCode"
                        filterable
                        clearable
                        placeholder="请选择已经发布的页面"
                        @change="selectPublishedPage"
                        ><el-option
                          v-for="targetPage in publishedPages"
                          :key="targetPage.pageCode || targetPage.pageId"
                          :label="publishedPageLabel(targetPage)"
                          :value="targetPage.pageCode" /></el-select
                      ><p class="field-hint">
                        运行时按页面编码读取目标页面的当前已发布版本；页面下线或撤回后将由服务端拒绝访问。
                      </p></el-form-item
                    ><el-form-item
                      v-if="
                        ['page', 'drilldown'].includes(
                          selectedWidget.interaction.onClick,
                        ) &&
                        selectedWidget.interaction.targetMode !== 'published'
                      "
                      label="目标路由"
                      ><el-input
                        v-model="selectedWidget.interaction.target"
                        placeholder="仅允许已授权站内路由"
                        @focus="beginHistory"
                        @change="endHistory" /></el-form-item
                    ><el-form-item
                      v-if="selectedWidget.interaction.onClick === 'browser'"
                      label="浏览器链接"
                      ><el-input
                        v-model="selectedWidget.interaction.target"
                        placeholder="例如 https://www.example.com"
                        @focus="beginHistory"
                        @change="endHistory"
                      />
                      <p class="field-hint">
                        仅允许 http:// 或 https://
                        链接，运行时在浏览器新标签页打开。
                      </p></el-form-item
                    ><el-form-item
                      v-if="selectedWidget.interaction.onClick === 'drilldown'"
                      label="钻取参数映射"
                      ><el-input
                        v-model="
                          selectedWidget.interaction.parameterMappingsJson
                        "
                        type="textarea"
                        :rows="4"
                        spellcheck="false"
                        placeholder='[{"sourceField":"direction","targetParameter":"direction"}]'
                        @focus="beginHistory"
                        @change="applyInteractionMappings"
                      />
                      <p class="field-hint">
                        点击数据项后将来源字段映射为目标页面查询参数，仅允许站内路由和纯字段名。
                      </p></el-form-item
                    ><el-form-item
                      v-if="
                        selectedWidget.interaction.onClick === 'self-drilldown'
                      "
                      label="组件逐级钻取配置"
                      ><el-input
                        v-model="selectedWidget.interaction.drilldownJson"
                        type="textarea"
                        :rows="7"
                        spellcheck="false"
                        placeholder='{"levels":[{"label":"组织","fieldMap":{"category":"department","value":"total"},"parameterMappings":[{"sourceField":"department","targetParameter":"departmentCode"}]}]}'
                        @focus="beginHistory"
                        @change="applyDrilldownJson"
                      />
                      <p class="field-hint">
                        每级只声明字段映射和参数/过滤器映射；点击后仍使用同一数据集和平台接口，不支持脚本或任意
                        SQL。
                      </p></el-form-item
                    ><template
                      v-if="selectedWidget.interaction.onClick === 'filter'"
                      ><el-form-item label="来源字段"
                        ><el-input
                          v-model="selectedWidget.interaction.sourceField"
                          placeholder="例如 direction；留空自动取分类字段"
                          @focus="beginHistory"
                          @change="endHistory" /></el-form-item
                      ><el-form-item label="目标组件"
                        ><el-select
                          v-model="selectedWidget.interaction.targetWidgetIds"
                          multiple
                          clearable
                          collapse-tags
                          placeholder="选择需要联动的组件"
                          ><el-option
                            v-for="target in schema.widgets.filter(
                              (item) => item.id !== selectedWidget.id,
                            )"
                            :key="target.id"
                            :label="
                              target.name ||
                              target.style?.title ||
                              meta(target.type).label
                            "
                            :value="target.id" /></el-select></el-form-item
                      ><el-form-item label="目标参数"
                        ><el-input
                          v-model="selectedWidget.interaction.targetParameter"
                          placeholder="目标数据集参数；留空使用来源字段"
                          @focus="beginHistory"
                          @change="endHistory" /></el-form-item
                      ><el-form-item label="目标过滤字段"
                        ><el-input
                          v-model="selectedWidget.interaction.targetField"
                          placeholder="目标未声明同名参数时使用；默认同来源字段"
                          @focus="beginHistory"
                          @change="endHistory" /></el-form-item></template
                  ></el-form>
                  <p
                    v-if="selectedWidget.interaction.onClick === 'popup'"
                    class="field-hint"
                  >
                    点击数据项后在平台内显示当前行详情；字段继续按数据集的脱敏/字典配置输出。
                  </p>
                </div>
                <div class="data-quality-note">
                  <el-icon><Lock /></el-icon
                  ><span
                    >交互只允许白名单动作，不支持任意脚本或网页标记；浏览器链接仅允许经校验的
                    HTTP/HTTPS 地址。</span
                  >
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
        <template v-else>
          <div class="inspector-head page-inspector-head">
            <div>
              <span class="inspector-eyebrow">页面属性</span>
              <h2>{{ page.pageName || "大屏页面" }}</h2>
            </div>
            <el-icon class="page-setting-icon"><Setting /></el-icon>
          </div>
          <div class="inspector-scroll">
            <div class="property-section">
              <div class="section-label">画布</div>
              <div class="position-grid">
                <label
                  >宽
                  <el-input-number
                    :model-value="canvasWidth"
                    :min="640"
                    :max="7680"
                    controls-position="right"
                    @focus="beginHistory"
                    @change="(value) => setCanvas('width', value)" /></label
                ><label
                  >高
                  <el-input-number
                    :model-value="canvasHeight"
                    :min="360"
                    :max="4320"
                    controls-position="right"
                    @focus="beginHistory"
                    @change="(value) => setCanvas('height', value)"
                /></label>
              </div>
              <el-form label-position="top"
                ><el-form-item label="普通运行画面适配"
                  ><el-select
                    v-model="schema.canvas.scaleMode"
                    @change="markDirty"
                    ><el-option label="自适应完整显示" value="contain" /><el-option
                      label="拉伸铺满"
                      value="stretch"
                  /></el-select>
                  <p class="field-hint">
                    等比适应保持设计比例，屏幕比例不同时可能留边；拉伸铺满完整覆盖运行窗口且不裁切组件。
                    仅影响普通运行页面，不影响全屏按钮进入的画面。
                  </p></el-form-item
                ><el-form-item label="全屏运行画面适配"
                  ><el-select
                    v-model="schema.canvas.fullscreenScaleMode"
                    @focus="beginHistory"
                    @change="endHistory"
                    ><el-option
                      v-for="option in fullscreenScaleOptions"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                  /></el-select>
                  <p class="field-hint">
                    {{ fullscreenScaleOptions.find((option) => option.value === schema.canvas.fullscreenScaleMode)?.description }}
                    仅影响全屏按钮进入的画面；保存并发布后应用于运行页和分享页的全屏展示。
                  </p></el-form-item
                ><div class="toggle-row page-canvas-toggle">
                  <span>隐藏运行滚动条</span>
                  <el-switch v-model="schema.canvas.hideScrollbar" @change="markDirty" />
                </div><el-form-item label="主题"
                  ><el-select v-model="schema.canvas.theme" @change="markDirty"
                    ><el-option label="深色" value="dark" /><el-option
                      label="浅色"
                      value="light" /></el-select></el-form-item
                ><el-form-item label="系统配色"
                  ><div class="palette-picker">
                    <button
                      v-for="palette in paletteOptions"
                      :key="palette.key"
                      type="button"
                      class="palette-option"
                      :class="{ active: schema.canvas.palette === palette.key }"
                      :title="palette.description"
                      @click="applyPalette(palette.key)"
                    >
                      <span class="palette-swatches"
                        ><i
                          v-for="color in palette.colors.slice(0, 5)"
                          :key="color"
                          :style="{ background: color }"
                        ></i></span
                      ><span>{{ palette.label }}</span>
                    </button>
                  </div>
                  <p class="field-hint">
                    系统配色只影响启用“使用系统配色”的组件；组件仍可单独覆盖。
                  </p></el-form-item
                ></el-form
              >
            </div>
            <div class="property-section background-property-section">
              <div class="section-label">页面背景</div>
              <el-form label-position="top">
                <el-form-item class="color-property-field" label="背景色"
                  ><div class="color-row background-color-row">
                    <DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                      :model-value="schema.canvas.background.color"
                      @active-change="(value) => updateCanvasColor('background.color', value)"
                      @change="(value) => finishCanvasColor('background.color', value)"
                    /></div
                ></el-form-item>
                <el-form-item label="背景图片"
                  ><div class="background-resource-row">
                    <DesignerTreeSelect
                      :model-value="schema.canvas.background.imageRef"
                      :data="imagePickerTree"
                      :props="resourceTreeProps"
                      node-key="value"
                      check-strictly
                      clearable
                      filterable
                      render-after-expand
                      placeholder="从资源管理图库选择"
                      empty-text="暂无可选图片"
                      @change="setBackgroundImage"
                    />
                    ><el-upload
                      :action="backgroundUploadUrl"
                      :headers="backgroundUploadHeaders"
                      accept=".png,.jpg,.jpeg,.webp,.gif"
                      :show-file-list="false"
                      :before-upload="beforeBackgroundUpload"
                      :on-success="handleBackgroundUpload"
                      :on-error="handleBackgroundUploadError"
                      ><el-button plain
                        ><el-icon><Upload /></el-icon>上传</el-button
                      ></el-upload
                    >
                  </div>
                  <p class="field-hint">
                    可从资源管理图库选择，也可直接上传平台图片；清空后只显示背景色。
                  </p></el-form-item
                >
                <el-form-item label="显示方式"
                  ><el-select
                    v-model="schema.canvas.background.mode"
                    @change="markDirty"
                    ><el-option
                      v-for="item in backgroundModeOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                  /></el-select>
                  <p class="field-hint">
                    {{ backgroundModeHint }}
                  </p></el-form-item
                >
                <el-form-item
                  v-if="
                    schema.canvas.background.mode !== 'tile' &&
                    schema.canvas.background.mode !== 'stretch'
                  "
                  label="图片位置"
                  ><el-select
                    v-model="schema.canvas.background.position"
                    @change="markDirty"
                    ><el-option
                      v-for="item in backgroundPositionOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value" /></el-select
                ></el-form-item>
              </el-form>
            </div>
            <div class="property-section">
              <div class="section-label section-label-inline">
                <span>页面过滤器</span
                ><el-button text type="primary" @click="addPageFilter"
                  >+ 添加</el-button
                >
              </div>
              <p v-if="!schema.filters.length" class="field-hint">
                通过声明参数过滤多个组件，运行时由服务端校验。
              </p>
              <div
                v-for="(filter, index) in schema.filters"
                :key="filter.id"
                class="page-filter-row"
              >
                <el-input
                  v-model="filter.label"
                  placeholder="显示名称"
                  @focus="beginHistory"
                  @change="endHistory"
                /><el-input
                  v-model="filter.parameter"
                  placeholder="数据集参数"
                  @focus="beginHistory"
                  @change="endHistory"
                /><el-select v-model="filter.type" @change="markDirty"
                  ><el-option label="文本" value="STRING" /><el-option
                    label="数字"
                    value="NUMBER" /><el-option
                    label="日期"
                    value="DATE" /><el-option
                    label="日期时间"
                    value="DATETIME" /></el-select
                ><el-input
                  v-model="filter.defaultValue"
                  placeholder="默认值"
                  @focus="beginHistory"
                  @change="endHistory"
                /><el-button text type="danger" @click="removePageFilter(index)"
                  ><el-icon><Close /></el-icon
                ></el-button>
              </div>
              <div
                v-for="filter in schema.filters"
                :key="`${filter.id}-targets`"
                class="page-filter-target-row"
              >
                <span
                  >{{ filter.label || filter.parameter || "过滤器" }}目标</span
                ><el-select
                  v-model="filter.targetWidgetIds"
                  multiple
                  clearable
                  collapse-tags
                  placeholder="全部数据组件"
                  @change="markDirty"
                  ><el-option
                    v-for="target in schema.widgets"
                    :key="target.id"
                    :label="
                      target.name ||
                      target.style?.title ||
                      meta(target.type).label
                    "
                    :value="target.id"
                /></el-select>
              </div>
            </div>
            <div class="property-section page-data-refresh-settings">
              <div class="section-label">数据自动刷新</div>
              <div class="toggle-row">
                <span><el-icon><Refresh /></el-icon>启用页面默认刷新</span>
                <el-switch v-model="schema.refresh.enabled" aria-label="启用页面默认刷新" @change="markDirty" />
              </div>
              <el-form v-if="schema.refresh.enabled" label-position="top">
                <el-form-item label="默认刷新方式">
                  <el-select v-model="schema.refresh.mode" @change="markDirty">
                    <el-option label="固定间隔" value="interval" />
                    <el-option label="每日定时" value="daily" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="schema.refresh.mode !== 'daily'" label="默认数据刷新间隔">
                  <div class="refresh-inherit">
                    <el-input-number v-model="schema.refresh.seconds" :min="5" :max="3600" :precision="0" controls-position="right" @change="markDirty" />
                    <span>秒</span>
                  </div>
                </el-form-item>
                <el-form-item v-else label="每日数据刷新时间">
                  <el-time-picker v-model="schema.refresh.at" format="HH:mm" value-format="HH:mm" placeholder="选择时间" @change="markDirty" />
                  <p class="field-hint">按运行端所在时区执行。</p>
                </el-form-item>
              </el-form>
              <p class="field-hint">组件数据刷新间隔为 0 时，使用这里的默认规则。定时更新数据；配置或版本变化时同步新布局。</p>
              <p v-if="!schema.refresh.enabled" class="field-hint">默认刷新已关闭。单独设置周期的组件和 WebSocket 实时推送仍可更新。</p>
              <p class="field-hint">保存并发布后应用于运行页和分享页。预览可手动刷新，不自动轮询；实时连接仍可接收推送。</p>
            </div>
            <div class="property-section">
              <div class="section-label">水印</div>
              <div class="toggle-row">
                <span>显示水印</span
                ><el-switch
                  v-model="schema.canvas.watermark.enabled"
                  @change="markDirty"
                />
              </div>
              <template v-if="schema.canvas.watermark.enabled"
                ><el-input
                  v-model="schema.canvas.watermark.text"
                  placeholder="水印文字"
                  @focus="beginHistory"
                  @change="endHistory" />
                <div class="color-row watermark-color">
                  <label>颜色</label
                  ><DesignerColorPicker :key="pageId + ':' + selectedIds.join(',')" @focus="beginHistory" @blur="endHistory"
                    :model-value="schema.canvas.watermark.color"
                    @active-change="(value) => updateCanvasColor('watermark.color', value)"
                    @change="(value) => finishCanvasColor('watermark.color', value)"
                  />
                </div>
                <label class="range-field"
                  >字号
                  <span>{{ schema.canvas.watermark.fontSize || 22 }}px</span
                  ><el-slider
                    :model-value="schema.canvas.watermark.fontSize || 22"
                    :min="10"
                    :max="96"
                    @update:model-value="
                      (value) =>
                        updateWatermarkSlider('fontSize', Number(value))
                    "
                    @change="finishSliderChange" /></label
                ><label class="range-field"
                  >旋转 <span>{{ schema.canvas.watermark.rotate || 0 }}°</span
                  ><el-slider
                    :model-value="schema.canvas.watermark.rotate || 0"
                    :min="-180"
                    :max="180"
                    @update:model-value="
                      (value) => updateWatermarkSlider('rotate', Number(value))
                    "
                    @change="finishSliderChange" /></label
                ><label class="range-field"
                  >透明度
                  <span
                    >{{
                      Math.round(
                        (schema.canvas.watermark.opacity ?? 0.12) * 100,
                      )
                    }}%</span
                  ><el-slider
                    :model-value="
                      (schema.canvas.watermark.opacity ?? 0.12) * 100
                    "
                    :min="0"
                    :max="100"
                    @update:model-value="
                      (value) =>
                        updateWatermarkSlider('opacity', Number(value) / 100)
                    "
                    @change="finishSliderChange" /></label
              ></template>
            </div>
            <div class="data-quality-note">
              <el-icon><InfoFilled /></el-icon
              ><span
                >当前页面使用草稿版本编辑，发布后运行端只读取不可变发布版本。</span
              >
            </div>
          </div>
        </template>
      </aside>
    </div>

    <div
      v-if="contextMenu.visible"
      class="designer-context-menu"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
      @pointerdown.stop
    >
      <button @click="runContextCommand('duplicate')">
        <el-icon><CopyDocument /></el-icon>复制
      </button>
      <button @click="runContextCommand('cut')">
        <el-icon><Scissor /></el-icon>剪切
      </button>
      <button @click="runContextCommand('delete')">
        <el-icon><Delete /></el-icon>删除
      </button>
      <span class="context-separator"></span>
      <button @click="runContextCommand('top')">
        <el-icon><Top /></el-icon>置顶
      </button>
      <button @click="runContextCommand('bottom')">
        <el-icon><Bottom /></el-icon>置底
      </button>
      <div class="group-context-actions">
        <el-tooltip content="组合 (⌘/Ctrl + G)">
          <button aria-label="组合" @click="runContextCommand('group')" :disabled="!canGroupSelection()"><el-icon><DesignerLayoutIcon name="group" /></el-icon></button>
        </el-tooltip>
        <el-tooltip content="取消组合 (⌘/Ctrl + Shift + G)">
          <button aria-label="取消组合" @click="runContextCommand('ungroup')" :disabled="!canUngroupSelection()"><el-icon><DesignerLayoutIcon name="ungroup" /></el-icon></button>
        </el-tooltip>
      </div>
      <button @click="runContextCommand('toggle-lock')">
        <el-icon><Lock /></el-icon
        >{{ contextMenu.widget?.state?.locked ? "解锁" : "锁定" }}
      </button>
      <button @click="runContextCommand('toggle-visible')">
        <el-icon><View /></el-icon
        >{{ contextMenu.widget?.state?.visible === false ? "显示" : "隐藏" }}
      </button>
      <span class="context-separator"></span>
      <button @click="runContextCommand('preview')">
        <el-icon><VideoPlay /></el-icon>预览组件
      </button>
      <button @click="runContextCommand('clear-linkage')">
        <el-icon><Connection /></el-icon>清空联动
      </button>
    </div>

    <el-dialog
      v-model="shortcutVisible"
      title="设计器快捷键"
      width="440px"
      align-center
    >
      <div class="shortcut-list">
        <div><kbd>⌘ / Ctrl</kbd><kbd>S</kbd><span>保存草稿</span></div>
        <div><kbd>⌘ / Ctrl</kbd><kbd>Z</kbd><span>撤销</span></div>
        <div>
          <kbd>⌘ / Ctrl</kbd><kbd>Shift</kbd><kbd>Z</kbd><span>重做</span>
        </div>
        <div><kbd>⌘ / Ctrl</kbd><kbd>C</kbd><span>复制组件</span></div>
        <div><kbd>⌘ / Ctrl</kbd><kbd>V</kbd><span>粘贴组件</span></div>
        <div><kbd>⌘ / Ctrl</kbd><kbd>G</kbd><span>组合选中组件</span></div>
        <div><kbd>⌘ / Ctrl</kbd><kbd>Shift</kbd><kbd>G</kbd><span>取消组合</span></div>
        <div><kbd>Shift / Ctrl / ⌘</kbd><span>点击加入或取消多选；空白处拖拽框选</span></div>
        <div><kbd>⌘/Ctrl + A</kbd><span>全选画布可见组件</span></div>
        <div><kbd>Shift + 方向键</kbd><span>整体移动 10 像素</span></div>
        <div><kbd>Delete</kbd><span>删除选中组件</span></div>
      </div>
    </el-dialog>
    <el-dialog
      v-model="componentPreview.visible"
      title="组件预览"
      width="560px"
      align-center
      destroy-on-close
    >
      <div v-if="componentPreview.widget" class="component-preview-dialog">
        <div
          class="component-preview-stage"
          :class="{
            'border-transparent':
              componentPreview.widget.style?.borderTransparent === true,
            'widget-embedded':
              componentPreview.widget.style?.embeddedMode === true,
            'title-image': hasTitleImage(componentPreview.widget),
            'title-hidden': !isWidgetTitleVisible(componentPreview.widget),
          }"
          :style="componentPreviewStyle(componentPreview.widget)"
        >
          <div
            v-if="isWidgetTitleVisible(componentPreview.widget)"
            class="preview-dialog-title"
            :style="widgetHeadingStyle(componentPreview.widget)"
          >
            <span
              v-if="previewWidgetTitle(componentPreview.widget)"
              >{{
                previewWidgetTitle(componentPreview.widget)
              }}</span
            ><small
              v-if="dashboardTitleText(componentPreview.widget.style?.subtitle)"
              :style="{
                color:
                  componentPreview.widget.style?.subtitleColor || '#aebccc',
                fontSize: `${Number(componentPreview.widget.style?.subtitleFontSize) || 10}px`,
                fontWeight:
                  Number(componentPreview.widget.style?.subtitleFontWeight) ||
                  400,
              }"
              >{{ dashboardTitleText(componentPreview.widget.style?.subtitle) }}</small
            >
          </div>
          <div
            v-if="
              ['metric-card', 'number-flip'].includes(
                componentPreview.widget.type,
              )
            "
            class="preview-dialog-kpi"
          >
            <span
              v-if="
                componentPreview.widget.type === 'number-flip' &&
                componentPreview.widget.style?.flipSplitDigits === true
              "
              class="preview-flip-cells"
              ><b
                v-for="(character, index) in previewFlipCharacters(
                  componentPreview.widget,
                )"
                :key="`${componentPreview.widget.id}-${index}`"
                >{{ character }}</b
              ></span
            ><span v-else>{{ previewMetric(componentPreview.widget) }}</span
            ><small>{{ componentPreview.widget.style?.unit || "" }}</small>
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'statistics'"
            class="preview-statistics"
            :class="[
              `stats-${componentPreview.widget.style?.statsMode || 'card'}`,
              `stats-layout-${componentPreview.widget.style?.statsLayout || 'vertical'}`,
              {
                'stats-label-after':
                  componentPreview.widget.style?.statsLabelPosition === 'after',
              },
            ]"
          >
            <div class="preview-statistics-main">
              <span v-if="dashboardStatisticsBodyTitleVisible(componentPreview.widget, previewWidgetTitle(componentPreview.widget))" class="preview-statistics-label">{{
                previewStatistic(
                  componentPreview.widget,
                  "label",
                  dashboardTitleText(componentPreview.widget.style?.title),
                )
              }}</span
              ><strong
                :style="{
                  fontSize: `${Number(componentPreview.widget.style?.statsValueSize) || 34}px`,
                }"
                >{{ componentPreview.widget.style?.statsPrefix || ""
                }}{{ previewStatistic(componentPreview.widget, "value", "—")
                }}<small class="preview-statistics-unit">{{
                  previewStatistic(
                    componentPreview.widget,
                    "suffix",
                    componentPreview.widget.style?.unit || "",
                  )
                }}</small></strong
              >
            </div>
            <em
              v-if="componentPreview.widget.style?.statsShowCompare !== false"
              :class="previewStatisticsCompareClass(componentPreview.widget)"
              >{{ previewStatisticsCompareText(componentPreview.widget) }}</em
            >
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'access-list'"
            class="preview-access-list preview-dialog-access"
            :class="`access-${componentPreview.widget.style?.accessVariant || 'person'}`"
          >
            <div
              v-for="(row, index) in previewAccessRows(componentPreview.widget)"
              :key="row.id || index"
              class="preview-access-row"
            >
              <img
                v-if="
                  componentPreview.widget.style?.accessShowAvatar !== false &&
                  previewAccessValue(componentPreview.widget, row, 'avatar')
                "
                :src="
                  dashboardResourceUrl(
                    previewAccessValue(componentPreview.widget, row, 'avatar'),
                  )
                "
                alt=""
              />
              <div class="preview-access-copy">
                <div class="preview-access-primary">
                  <b>{{
                    previewAccessValue(componentPreview.widget, row, "title") ||
                    "—"
                  }}</b
                  ><span>{{
                    previewAccessValue(componentPreview.widget, row, "subtitle")
                  }}</span
                  ><time>{{
                    previewAccessValue(componentPreview.widget, row, "time")
                  }}</time>
                </div>
                <small>{{
                  previewAccessValue(componentPreview.widget, row, "company") ||
                  "—"
                }}</small>
              </div>
              <em
                :class="{
                  success: previewAccessStatusSuccess(
                    componentPreview.widget,
                    row,
                  ),
                  neutral: !previewAccessStatusSuccess(
                    componentPreview.widget,
                    row,
                  ),
                }"
                >{{ previewAccessStatusText(componentPreview.widget, row) }}</em
              >
            </div>
            <div
              v-if="!previewAccessRows(componentPreview.widget).length"
              class="preview-access-empty"
            >
              暂无进出场记录
            </div>
          </div>
          <DashboardCarousel v-else-if="componentPreview.widget.type === 'carousel'" :rows="previewCarouselRows(componentPreview.widget)" :columns="previewColumns(componentPreview.widget)" :options="componentPreview.widget.style || {}" :format-value="previewValue" />
          <DashboardDataTable
            v-else-if="componentPreview.widget.type === 'table'"
            :rows="widgetPreviewRows(componentPreview.widget)" :columns="previewColumns(componentPreview.widget)"
            :auto-scroll="componentPreview.widget.style?.tableAutoScroll === true"
            :seconds-per-row="componentPreview.widget.style?.tableScrollSeconds"
            :format-value="previewValue" :interactive="false"
          />
          <div
            v-else-if="
              [
                'table',
                'advanced-table',
                'rank-table',
                'carousel-table',
                'alert-list',
                'realtime-list',
              ].includes(componentPreview.widget.type)
            "
            class="designer-table-preview preview-dialog-table"
            :class="{
              stripe:
                componentPreview.widget.type === 'advanced-table' &&
                componentPreview.widget.style?.advancedStripe !== false,
            }"
            :style="{
              '--preview-row-height': `${componentPreview.widget.type === 'advanced-table' ? Number(componentPreview.widget.style?.advancedRowHeight) || 34 : 30}px`,
            }"
          >
            <table>
              <thead
                v-if="
                  componentPreview.widget.type !== 'advanced-table' ||
                  componentPreview.widget.style?.advancedShowHeader !== false
                "
              >
                <tr>
                  <th
                    v-if="
                      componentPreview.widget.type === 'advanced-table' &&
                      componentPreview.widget.style?.advancedShowIndex
                    "
                  >
                    #
                  </th>
                  <th
                    v-for="(column, columnIndex) in previewColumns(componentPreview.widget)"
                    :key="column.name"
                    :class="{
                      'table-primary-column':
                        componentPreview.widget.type === 'advanced-table' &&
                        columnIndex === 0,
                    }"
                  >
                    {{ column.title || column.name }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(row, index) in previewTableRows(
                    componentPreview.widget,
                  )"
                  :key="row.id || index"
                >
                  <td
                    v-if="
                      componentPreview.widget.type === 'advanced-table' &&
                      componentPreview.widget.style?.advancedShowIndex
                    "
                  >
                    {{ index + 1 }}
                  </td>
                  <td
                    v-for="(column, columnIndex) in previewColumns(componentPreview.widget)"
                    :key="column.name"
                    :class="{
                      'table-primary-column':
                        componentPreview.widget.type === 'advanced-table' &&
                        columnIndex === 0,
                    }"
                  >
                    <span :style="componentPreview.widget.type === 'advanced-table' ? dashboardTableCellStyle(componentPreview.widget.style?.tableRules, column.name, row[column.name]) : undefined">{{ previewValue(row[column.name], column) }}{{ componentPreview.widget.type === 'advanced-table' ? dashboardTableSuffix(componentPreview.widget.style?.tableRules, column.name, row[column.name]) : '' }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <DashboardRingText
            v-else-if="componentPreview.widget.type === 'ring-text'"
            :items="previewRingText(componentPreview.widget)"
            :options="componentPreview.widget.style || {}"
            :center-text="String(previewStatistic(componentPreview.widget, 'value', '工程驾驶舱'))"
          />
          <div
            v-else-if="componentPreview.widget.type === 'milestone-timeline'"
            class="preview-milestone-timeline"
            :class="{
              'labels-above': componentPreview.widget.style?.timelineLabelPosition === 'above',
              'layout-spread': componentPreview.widget.style?.timelineLayout === 'spread',
            }"
          >
            <div
              v-for="(item, index) in previewMilestones(componentPreview.widget)"
              :key="index"
              :class="{ active: item.active, done: item.done }"
            >
              <i></i><b>{{ item.label }}</b>
              <span v-if="componentPreview.widget.style?.timelineShowDates !== false">{{ item.startDate || '—' }}</span>
              <small v-if="componentPreview.widget.style?.timelineShowEndDate === true">{{ item.endDate || '—' }}</small>
            </div>
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'image'"
            class="preview-dialog-image"
          >
            <img v-if="componentPreview.widget.style?.imageRef"
              class="designer-image-preview"
              :src="dashboardResourceUrl(componentPreview.widget.style.imageRef)"
              :style="{
                objectFit: componentPreview.widget.style?.imageFit || 'contain',
                objectPosition: componentPreview.widget.style?.imagePosition || 'center',
              }"
              alt="" draggable="false" />
            <span v-else>请选择图库资源</span>
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'current-time'"
            class="preview-clock-content preview-dialog-clock"
          >
            <el-icon
              v-if="componentPreview.widget.style?.clockShowIcon !== false"
              ><Clock /></el-icon
            ><strong>{{ previewCurrentTime(componentPreview.widget) }}</strong>
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'weather'"
            class="preview-dialog-weather"
          >
            <Cloudy />
            {{ previewWeather(componentPreview.widget, "temperature", "—")
            }}{{ componentPreview.widget.style?.temperatureUnit || "℃"
            }}<small
              >{{
                previewWeather(
                  componentPreview.widget,
                  "condition",
                  "待接入天气数据",
                )
              }}
              ·
              {{
                previewWeather(componentPreview.widget, "city", "未设置城市")
              }}</small
            >
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'color-block'"
            class="preview-dialog-color-block"
          >
            <span
              :style="{
                background:
                  previewColorBlockValue(componentPreview.widget),
              }"
            ></span
            ><small>{{
              previewStatistic(componentPreview.widget, "label", componentPreview.widget.style?.blockLabel || "状态颜色")
            }}</small>
          </div>
          <div
            v-else-if="
              componentPreview.widget.type === 'text' ||
              componentPreview.widget.type === 'rich-text'
            "
            class="preview-dialog-text"
          >
            {{ dashboardTitleText(componentPreview.widget.style?.text) }}
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'button'"
            class="preview-button"
            :style="dashboardButtonStyle(componentPreview.widget.style, dashboardResourceUrl(componentPreview.widget.style?.titleImageRef))"
          >
            {{ dashboardButtonLabel(componentPreview.widget.style) }}
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'icon'"
            class="preview-dialog-icon"
          >
            <DashboardIcon :options="componentPreview.widget.style" :name="componentPreview.widget.name || '图标'" />
          </div>
          <div
            v-else-if="componentPreview.widget.type === 'custom-html'"
            class="preview-dialog-custom-html"
          >
            <iframe
              :srcdoc="customHtmlPreview(componentPreview.widget)"
              sandbox="allow-scripts"
              title="自定义内容预览"
            ></iframe>
          </div>
          <video :key="`${componentPreview.widget.id}-${componentPreview.widget.style?.videoRef}-${componentPreview.widget.style?.autoplay}`" v-else-if="componentPreview.widget.type === 'video' && componentPreview.widget.style?.videoRef"
            class="designer-video-preview" :src="dashboardResourceUrl(componentPreview.widget.style.videoRef)"
            :poster="dashboardResourceUrl(componentPreview.widget.style.posterRef)" :autoplay="componentPreview.widget.style.autoplay" @loadeddata="dashboardVideoAutoplay($event, componentPreview.widget.style)"
            :loop="componentPreview.widget.style.loop" :muted="componentPreview.widget.style.muted !== false" :controls="componentPreview.widget.style.controls !== false" />
          <div v-else-if="componentPreview.widget.type === 'video'" class="preview-dialog-media">请选择平台视频资源</div>
          <DashboardEmbeddedPage v-else-if="componentPreview.widget.type === 'iframe'" :src="componentPreview.widget.style?.iframeRef" />
          <DashboardMapPreview
            v-else-if="isMapWidget(componentPreview.widget.type)"
            :widget="componentPreview.widget"
            :rows="widgetPreviewRows(componentPreview.widget)"
            :palette="paletteOptions.find((item) => item.key === schema.canvas.palette)?.colors || paletteOptions[0].colors"
            :format-value="(field, value) => previewValue(value, previewField(componentPreview.widget, field))"
          />
          <div
            v-else-if="componentPreview.widget.type === 'word-cloud'"
            class="preview-word-cloud"
          >
            <span
              v-for="(item, index) in previewWordCloud(componentPreview.widget)"
              :key="`${item.label}-${index}`"
              :style="{ fontSize: `${item.size}px`, color: item.color }"
              >{{ item.label }}</span
            >
          </div>
          <DashboardTabs v-else-if="componentPreview.widget.type === 'tabs'" :tabs="previewTabs(componentPreview.widget)" :fallback="componentPreview.widget.style?.tabContent || ''" />
          <DashboardFilterForm v-else-if="['filter-form', 'designer-form', 'online-form'].includes(componentPreview.widget.type)" :fields="previewFormFields(componentPreview.widget)" />
          <template v-else-if="componentPreview.widget.type === 'border'"></template>
          <div
            v-else-if="componentPreview.widget.type === 'decoration'"
            class="preview-decoration"
          ></div>
          <DashboardGantt v-else-if="componentPreview.widget.type === 'gantt-chart'" :rows="widgetPreviewRows(componentPreview.widget)" :field-map="componentPreview.widget.binding?.fieldMap || {}" :options="componentPreview.widget.style || {}" />
          <div
            v-else-if="isPreviewChart(componentPreview.widget.type)"
            ref="componentPreviewChartElement"
            class="component-preview-chart"
          ></div>
          <div v-else class="preview-dialog-empty">
            暂不支持此组件的独立预览，请使用整页预览核对。
          </div>
          <div
            v-if="widgetPreviewStatus(componentPreview.widget)"
            class="designer-data-preview-state dialog-state"
          >
            {{ widgetPreviewStatus(componentPreview.widget) }}
          </div>
        </div>
        <p class="field-hint">
          这是设计态组件预览，运行态将按数据集结果和发布版本渲染。
        </p>
      </div>
    </el-dialog>
    <DashboardComponentGuide v-model="componentGuideVisible" :widget="selectedWidget" />
    <DesignerIconPicker
      v-model="iconPickerVisible"
      :selected-icon-name="iconPickerWidget?.style?.iconName || 'star'"
      :selected-image-ref="iconPickerWidget?.style?.imageRef || ''"
      :assets="imageAssets"
      :loading="iconAssetsLoading"
      @refresh="loadIconAssets"
      @select="applyIconSelection"
    />
    <input
      ref="importInput"
      class="hidden-file-input"
      type="file"
      accept="application/json,.json"
      @change="importSchema"
    />
  </div>
</template>

<script setup>
import DesignerTreeSelect from "./DesignerTreeSelect.vue";
import DesignerLayoutIcon from "./DesignerLayoutIcon.vue";
import DesignerLayerTree from "./DesignerLayerTree.vue";
import DesignerColorPicker from "./DesignerColorPicker.vue";
import DesignerIconPicker from "./DesignerIconPicker.vue";
import DashboardIcon from "@/components/DashboardIcon/index.vue";
import { arrangeDashboardWidgets, dashboardLayoutUnitCount, translateDashboardWidgets } from "@/utils/dashboardLayout";
import { getDashboardSelectionBounds, resizeDashboardGroup, translateDashboardClones } from "@/utils/dashboardGrouping";
import { normalizeDashboardGroups, copyDashboardGroupMetadata } from "@/utils/dashboardGroupMetadata";
import DashboardDataTable from "@/components/DashboardDataTable/index.vue";
import DashboardComponentGuide from "@/components/DashboardComponentGuide/index.vue";
import DashboardEmbeddedPage from "@/components/DashboardEmbeddedPage/index.vue";
import { dashboardComponentCapabilities, dashboardPropertyVisible } from "@/utils/dashboardComponentCapabilities";
import { dashboardTableCellStyle, dashboardTableSuffix } from "@/utils/dashboardTableRules";
import { dashboardVideoAutoplay, dashboardColorBlockValue, dashboardButtonStyle, dashboardButtonUsesImage, dashboardButtonLabel, dashboardTitleText, dashboardWidgetTitleVisible, dashboardStatisticsBodyTitleVisible, dashboardStatisticsField, normalizeDashboardStatisticsStyle, dashboardFormOptions, dashboardCarouselRows, dashboardHeadingHeight } from "@/utils/dashboardPresentation";
import { formatDashboardMetricValue, formatDashboardMetricDisplay } from "@/utils/dashboardMetric";
import { mergeDashboardChartOption, applyDashboardChartProperties } from "@/utils/dashboardChartOptions";
import DashboardMapPreview from "@/components/DashboardMapPreview/index.vue";
import DashboardGantt from "@/components/DashboardGantt/index.vue";
import DashboardRingText from "@/components/DashboardRingText/index.vue";
import DashboardFilterForm from "@/components/DashboardFilterForm/index.vue";
import DashboardTabs from "@/components/DashboardTabs/index.vue";
import DashboardCarousel from "@/components/DashboardCarousel/index.vue";
import { dashboardIconOptions } from "@/utils/dashboardIcons";
import { dashboardLibraryImages, dashboardIconImageUrl, dashboardIconImageLabel, dashboardIconUsesImage } from "@/utils/dashboardIconLibrary";
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import useTagsViewStore from "@/store/modules/tagsView";
import * as echarts from "echarts";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  ArrowDown,
  ArrowUp,
  Back,
  Bottom,
  Calendar,
  Clock,
  Close,
  Cloudy,
  Connection,
  CopyDocument,
  DataAnalysis,
  Delete,
  DocumentChecked,
  DocumentCopy,
  Download,
  FullScreen,
  Grid,
  Hide,
  InfoFilled,
  FolderOpened,
  Link,
  Lock,
  MagicStick,
  Menu,
  MoreFilled,
  Monitor,
  Picture,
  MapLocation,
  Plus,
  Pointer,
  Promotion,
  Rank,
  Refresh,
  RefreshLeft,
  RefreshRight,
  Scissor,
  Search,
  Setting,
  Sort,
  Star,
  Timer,
  Top,
  Unlock,
  Upload,
  VideoCamera,
  VideoPlay,
  View,
  ZoomIn,
  ZoomOut,
  DataLine,
  TrendCharts,
  PieChart,
  Histogram,
  DataBoard,
  List,
  Tickets,
  Edit,
  PriceTag,
} from "@element-plus/icons-vue";
import {
  getDashboardPage,
  listDashboardAssets,
  listDashboardDatasetGroups,
  listDashboardDatasets,
  listDashboardMaps,
  listDashboardPages,
  listPublishedDashboardPages,
  previewDashboardDataset,
  previewDashboardWidgetData,
  publishDashboardPage,
  saveDashboardDraft,
  copyDashboardPage,
  updateDashboardPage,
} from "@/api/dashboard";
import { getToken } from "@/utils/auth";
import {
  dashboardBackgroundLayout,
  dashboardResourceUrl,
  formatDashboardDate,
  formatDashboardDateTime,
  formatDashboardFieldValue,
} from "@/utils/dashboard";
import {
  fullscreenScaleOptions,
  normalizeFullscreenScaleMode,
} from "@/utils/dashboardViewport";
import auth from "@/plugins/auth";
import { isDashboardPageRunnable } from "@/utils/dashboardPageAvailability";

const route = useRoute();
const router = useRouter();
const pageId = Number(route.params.pageId);
const designerTagPath = route.path;
const canCopyPage = computed(() => auth.hasPermi("dashboard:page:add"));
const loading = ref(true);
const saving = ref(false);
const savingPageName = ref(false);
const publishing = ref(false);
const previewing = ref(false);
const draftDirty = ref(false);
const page = reactive({});
const canRunPage = computed(() => isDashboardPageRunnable(page));
const checkingRuntime = ref(false);
const datasets = ref([]);
const publishedPages = ref([]);
const datasetGroups = ref([]);
const imageAssets = ref([]);
const videoAssets = ref([]);
const mapResources = ref([]);
const datasetTreeProps = {
  value: "value",
  label: "label",
  children: "children",
  disabled: "disabled",
};
const resourceTreeProps = datasetTreeProps;
const leftTab = ref("components");
const inspectorTab = ref("basic");
const componentGuideVisible = ref(false);
const iconPickerVisible = ref(false);
const iconPickerTargetId = ref("");
const iconPickerWidget = computed(() => widgetById(iconPickerTargetId.value));
const iconAssetsLoading = ref(false);
const selectedIds = ref([]);
const memberEditId = ref("");
const clipboardGroups = ref({});
const multiSelectMode = ref(false);
const selectedLayoutWidgets = computed(() => selectedIds.value.map(widgetById).filter(Boolean));
const selectedHasLocked = computed(() => selectedLayoutWidgets.value.some(widget => widget.state?.locked));
const selectedGroupId = computed(() => {
  const widgets = selectedLayoutWidgets.value;
  const groupId = widgets[0]?.groupId;
  return !memberEditId.value && groupId && widgets.every(widget => widget.groupId === groupId) ? groupId : "";
});
const selectedGroupBounds = computed(() => groupSelectionVisualBounds(selectedLayoutWidgets.value));
const selectedGroupStyle = computed(() => {
  const bounds = selectedGroupBounds.value;
  return bounds ? { left: `${bounds.x}px`, top: `${bounds.y}px`, width: `${bounds.w}px`, height: `${bounds.h}px`, zIndex: Math.max(1, ...schema.widgets.map(widget => Number(widget.layout?.z) || 1)) + 1 } : {};
});
const alignmentActions = [
  { command: "left", label: "左对齐" },
  { command: "center", label: "水平居中" },
  { command: "right", label: "右对齐" },
  { command: "top", label: "顶部对齐" },
  { command: "middle", label: "垂直居中" },
  { command: "bottom", label: "底部对齐" },
  { command: "distribute-x", label: "水平平均分布" },
  { command: "distribute-y", label: "纵向平均分布" },
];
const marquee = reactive({ active: false, moved: false, startX: 0, startY: 0, x: 0, y: 0, initialIds: [] });
const marqueeStyle = computed(() => ({
  left: `${Math.min(marquee.startX, marquee.x)}px`,
  top: `${Math.min(marquee.startY, marquee.y)}px`,
  width: `${Math.abs(marquee.x - marquee.startX)}px`,
  height: `${Math.abs(marquee.y - marquee.startY)}px`,
}));
const staticRowsEditorText = ref("");
const layerKeyword = ref("");
const showGrid = ref(true);
const snapToGrid = ref(true);
const panMode = ref(false);
const spacePressed = ref(false);
const canvasScale = ref(0.58);
const zoomOptions = [20, 25, 50, 75, 100, 120];
const canvasViewport = ref();
const canvasElement = ref();
const importInput = ref();
const pageNameInput = ref();
const shortcutVisible = ref(false);
const history = ref([]);
const future = ref([]);
const historyPending = ref(null);
const clipboardWidgets = ref([]);
const paletteDragType = ref("");
const contextMenu = reactive({ visible: false, x: 0, y: 0, widget: null });
const componentPreview = reactive({ visible: false, widget: null });
const componentPreviewChartElement = ref();
let componentPreviewChartInstance = null;
const designChartElements = {};
const designChartInstances = {};
let designRenderTimer = null;
const designPreviewStates = reactive({});
const designPreviewCache = new Map();
const batchStyle = reactive({
  color: "#35d4b0",
  backgroundColor: "rgba(17,24,39,.78)",
  opacity: 1,
  borderRadius: 8,
});
const layerDragId = ref("");
const layerDragMemberOnly = ref(false);
const drag = reactive({
  mode: "",
  moved: false,
  toggleOnClick: null,
  widget: null,
  handle: "",
  startX: 0,
  startY: 0,
  originals: {},
  before: null,
});

const designerDatasetTypes = [
  { value: "SQL", label: "SQL 数据集" },
  { value: "API", label: "API 数据集" },
  { value: "JSON", label: "JSON 数据集" },
  { value: "WEBSOCKET", label: "WebSocket 数据集" },
];

function buildDesignerDatasetTree(items) {
  return designerDatasetTypes.map((type) => {
    const typeItems = (items || []).filter(
      (item) => String(item.dataType || "").toUpperCase() === type.value,
    );
    const groups = new Map();
    typeItems.forEach((item) => {
      const groupCode = String(item.groupCode || "");
      const groupKey = groupCode || "__root__";
      if (!groups.has(groupKey)) {
        groups.set(groupKey, {
          value: `__dataset_group__${type.value}__${groupKey}`,
          label:
            item.groupName ||
            datasetGroups.value.find(
              (group) => String(group.groupCode || "") === groupCode,
            )?.groupName ||
            item.groupCode ||
            "根目录",
          disabled: true,
          children: [],
        });
      }
      groups.get(groupKey).children.push({
        value: String(item.datasetCode || ""),
        label: `${item.datasetName || item.datasetCode} · ${item.datasetCode}`,
        kind: "dataset",
      });
    });
    return {
      value: `__dataset_type__${type.value}`,
      label: type.label,
      disabled: true,
      children: [...groups.values()],
    };
  });
}

function buildDesignerResourceTree(items, rootLabel, rootKey) {
  const groups = new Map();
  (items || []).forEach((item) => {
    const groupKey = String(item.folderId ?? "0");
    if (!groups.has(groupKey)) {
      groups.set(groupKey, {
        value: `__resource_group__${rootKey}__${groupKey}`,
        label: item.folderName || "根目录",
        disabled: true,
        children: [],
      });
    }
    const name = item.assetName || item.mapName || item.resourcePath;
    const code = item.assetCode || item.mapCode || "";
    groups.get(groupKey).children.push({
      value: String(item.resourcePath || ""),
      label: code ? `${name} · ${code}` : String(name || "未命名资源"),
      kind: "resource",
    });
  });
  return [
    {
      value: `__resource_root__${rootKey}`,
      label: rootLabel,
      disabled: true,
      children: [...groups.values()],
    },
  ];
}

const datasetPickerTree = computed(() =>
  buildDesignerDatasetTree(datasets.value),
);
const imagePickerTree = computed(() =>
  buildDesignerResourceTree(imageAssets.value, "图片资源", "image"),
);
const videoPickerTree = computed(() =>
  buildDesignerResourceTree(videoAssets.value, "视频资源", "video"),
);
const mapPickerTree = computed(() =>
  buildDesignerResourceTree(mapResources.value, "地图资源", "map"),
);
const canvasPan = reactive({
  active: false,
  startX: 0,
  startY: 0,
  scrollLeft: 0,
  scrollTop: 0,
  moved: false,
  suppressClick: false,
});
const pageNameEditing = ref(false);
const pageNameDraft = ref("");

const backgroundUploadUrl = `${import.meta.env.VITE_APP_BASE_API || "/dev-api"}/common/upload`;
const backgroundUploadHeaders = { Authorization: `Bearer ${getToken() || ""}` };
const paletteOptions = [
  {
    key: "teal",
    label: "青绿色",
    description: "适合工程和运营驾驶舱",
    colors: ["#35d4b0", "#5b8ff9", "#e7ab47", "#c678dd", "#ef8d8d", "#53b7d6"],
  },
  {
    key: "ocean",
    label: "深海蓝",
    description: "深色背景的冷色调",
    colors: ["#4cc9f0", "#4361ee", "#4895ef", "#72efdd", "#bde0fe", "#90be6d"],
  },
  {
    key: "amber",
    label: "琥珀橙",
    description: "强调预警和进度",
    colors: ["#f6bd16", "#f08a24", "#e86850", "#5b8ff9", "#61ddaa", "#9270ca"],
  },
  {
    key: "purple",
    label: "紫罗兰",
    description: "分析和对比场景",
    colors: ["#9270ca", "#b37feb", "#5b8ff9", "#61ddaa", "#f6bd16", "#e86850"],
  },
  {
    key: "mono",
    label: "中性灰",
    description: "打印和汇报场景",
    colors: ["#d9e2ec", "#9fb3c8", "#627d98", "#486581", "#334e68", "#bcccdc"],
  },
];
const backgroundModeOptions = [
  {
    value: "fill",
    label: "填充",
    description: "保持图片比例并铺满画布，超出部分会被裁切",
  },
  {
    value: "fit",
    label: "适应",
    description: "保持图片比例并完整显示，空白区域使用背景色",
  },
  {
    value: "stretch",
    label: "拉伸",
    description: "图片拉伸到画布尺寸，可能改变原始比例",
  },
  {
    value: "tile",
    label: "平铺",
    description: "按图片原始大小重复排列，适合纹理和图案",
  },
  {
    value: "center",
    label: "居中",
    description: "按图片原始大小居中显示，不进行缩放",
  },
];
const backgroundPositionOptions = [
  { value: "center", label: "居中" },
  { value: "top", label: "顶部" },
  { value: "bottom", label: "底部" },
  { value: "left", label: "左侧" },
  { value: "right", label: "右侧" },
];
const formFieldTypes = [
  { value: "STRING", label: "文本" },
  { value: "NUMBER", label: "数字" },
  { value: "DATE", label: "日期" },
  { value: "DATETIME", label: "日期时间" },
  { value: "SELECT", label: "下拉选项" },
  { value: "RADIO", label: "单选选项" },
];
const schema = reactive(defaultSchema());
let lastSchemaSnapshot = JSON.stringify(schema);
const resizeHandles = ["nw", "n", "ne", "e", "se", "s", "sw", "w"];

const componentGroups = [
  {
    key: "data",
    label: "数据组件",
    icon: DataBoard,
    items: [
      {
        type: "metric-card",
        label: "指标卡片",
        icon: PriceTag,
        tone: "teal",
        description: "突出显示单个指标值",
      },
      {
        type: "number-flip",
        label: "数字翻牌",
        icon: DataLine,
        tone: "blue",
        description: "动态数字展示",
      },
      {
        type: "statistics",
        label: "统计概览",
        icon: DataAnalysis,
        tone: "amber",
        description: "标题、数值、单位和同比状态",
      },
      {
        type: "table",
        label: "数据表格",
        icon: List,
        tone: "violet",
        description: "展示多行明细数据",
      },
      {
        type: "advanced-table",
        label: "高级表格",
        icon: List,
        tone: "cyan",
        description: "带表头、序号、斑马纹和滚动的表格",
      },
      {
        type: "access-list",
        label: "图文记录列表",
        icon: Tickets,
        tone: "blue",
        description: "人员或车辆进出场图文明细",
      },
      {
        type: "carousel-table",
        label: "轮播表格",
        icon: List,
        tone: "blue",
        description: "按间隔自动轮播多行数据",
      },
      {
        type: "carousel",
        label: "卡片轮播",
        icon: Timer,
        tone: "purple",
        description: "按间隔轮播数据卡片",
      },
      { type: "gantt-chart", label: "计划甘特图", icon: Timer, tone: "blue", description: "基准与实际日期、依赖关系及当前日期标线" },
      {
        type: "milestone-timeline",
        label: "里程碑时间轴",
        icon: Timer,
        tone: "blue",
        description: "按阶段展示计划日期和完成状态",
      },
      {
        type: "rank-table",
        label: "排名表",
        icon: Tickets,
        tone: "amber",
        description: "按数值排序展示",
      },
      {
        type: "alert-list",
        label: "告警列表",
        icon: Tickets,
        tone: "red",
        description: "展示告警事件",
      },
      {
        type: "realtime-list",
        label: "实时列表",
        icon: TrendCharts,
        tone: "green",
        description: "WebSocket 实时事件",
      },
    ],
  },
  {
    key: "charts",
    label: "图表组件",
    icon: TrendCharts,
    items: [
      {
        type: "line-chart",
        label: "折线图",
        icon: TrendCharts,
        tone: "blue",
        description: "趋势和时间序列",
      },
      {
        type: "bar-chart",
        label: "柱状图",
        icon: Histogram,
        tone: "teal",
        description: "分类对比",
      },
      {
        type: "custom-chart",
        label: "通用图表",
        icon: DataAnalysis,
        tone: "purple",
        description: "使用受控图表配置生成通用图表",
      },
      {
        type: "pie-chart",
        label: "饼图",
        icon: PieChart,
        tone: "violet",
        description: "占比和构成",
      },
      {
        type: "ring-chart",
        label: "环形图",
        icon: PieChart,
        tone: "pink",
        description: "环形占比",
      },
      {
        type: "gauge",
        label: "仪表盘",
        icon: DataLine,
        tone: "amber",
        description: "区间和百分比",
      },
      {
        type: "progress",
        label: "进度图",
        icon: DataBoard,
        tone: "green",
        description: "完成度和进度",
      },
      {
        type: "funnel",
        label: "漏斗图",
        icon: DataBoard,
        tone: "orange",
        description: "阶段转化",
      },
      {
        type: "radar",
        label: "雷达图",
        icon: DataLine,
        tone: "cyan",
        description: "多维能力对比",
      },
      {
        type: "scatter",
        label: "散点图",
        icon: DataLine,
        tone: "purple",
        description: "分布和相关性",
      },
      {
        type: "area-chart",
        label: "面积图",
        icon: TrendCharts,
        tone: "blue",
        description: "趋势和累计变化",
      },
      {
        type: "pictorial-chart",
        label: "象形图",
        icon: DataBoard,
        tone: "orange",
        description: "图形化数量对比",
      },
      {
        type: "treemap-chart",
        label: "矩形树图",
        icon: Grid,
        tone: "teal",
        description: "用矩形面积展示分类占比",
      },
      {
        type: "calendar-chart",
        label: "日历热力图",
        icon: Calendar,
        tone: "blue",
        description: "按日期展示数值热力分布",
      },
      {
        type: "word-cloud",
        label: "文字云",
        icon: Edit,
        tone: "pink",
        description: "按权重突出关键词",
      },
      {
        type: "bar3d-chart",
        label: "3D 柱形图",
        icon: Histogram,
        tone: "orange",
        description: "受控等距 3D 柱形效果",
      },
    ],
  },
  {
    key: "visual",
    label: "可视化组件",
    icon: MapLocation,
    items: [
      {
        type: "map-chart",
        label: "受控地图",
        icon: MapLocation,
        tone: "cyan",
        description: "基于平台 GeoJSON 和数据集坐标的地图",
      },
      {
        type: "map-flow",
        label: "飞线地图",
        icon: MapLocation,
        tone: "blue",
        description: "展示地点之间的流动和连接",
      },
      {
        type: "map-bar",
        label: "柱形地图",
        icon: Histogram,
        tone: "amber",
        description: "在地图点位上按数值显示柱形",
      },
      {
        type: "map-heat",
        label: "热力地图",
        icon: DataAnalysis,
        tone: "red",
        description: "按点位密度显示热力",
      },
      {
        type: "map-ranking",
        label: "柱形排名地图",
        icon: Tickets,
        tone: "purple",
        description: "地图点位排名和数值对比",
      },
      {
        type: "map-timeline",
        label: "时间轴飞线",
        icon: Timer,
        tone: "green",
        description: "按分组时间播放飞线数据",
      },
      {
        type: "weather",
        label: "天气预报",
        icon: Cloudy,
        tone: "blue",
        description: "使用受控 API/JSON 数据展示天气",
      },
      {
        type: "current-time",
        label: "当前时间",
        icon: Clock,
        tone: "teal",
        description: "显示运行端本地时间",
      },
      {
        type: "ring-text",
        label: "轨道环形文字",
        icon: Refresh,
        tone: "violet",
        description: "沿圆形轨道旋转展示文字",
      },
      {
        type: "color-block",
        label: "颜色块",
        icon: DataAnalysis,
        tone: "amber",
        description: "使用数据或静态配置展示颜色状态",
      },
    ],
  },
  {
    key: "basic",
    label: "基础组件",
    icon: Menu,
    items: [
      {
        type: "filter-form",
        label: "查询表单",
        icon: Tickets,
        tone: "teal",
        description: "通过页面参数联动数据组件",
      },
      {
        type: "designer-form",
        label: "设计器表单",
        icon: Tickets,
        tone: "blue",
        description: "可视化配置的受控查询表单",
      },
      {
        type: "online-form",
        label: "在线表单（查询）",
        icon: Tickets,
        tone: "violet",
        description: "在线输入并联动页面数据，不写回业务系统",
      },
      {
        type: "tabs",
        label: "选项卡",
        icon: Menu,
        tone: "violet",
        description: "在运行态切换内容面板",
      },
      {
        type: "text",
        label: "文本",
        icon: Edit,
        tone: "slate",
        description: "标题或说明文字",
      },
      {
        type: "rich-text",
        label: "富文本",
        icon: Edit,
        tone: "slate",
        description: "受控纯文本说明，不执行网页标记",
      },
      {
        type: "icon",
        label: "图标",
        icon: Star,
        tone: "amber",
        description: "平台图标、静态图片与 GIF 动态图标",
      },
      {
        type: "button",
        label: "按钮",
        icon: Promotion,
        tone: "blue",
        description: "触发联动或站内跳转",
      },
      {
        type: "image",
        label: "图片",
        icon: Picture,
        tone: "slate",
        description: "平台审核图片资源",
      },
      {
        type: "video",
        label: "视频",
        icon: VideoCamera,
        tone: "violet",
        description: "平台视频资源，支持静音自动播放",
      },
      {
        type: "iframe",
        label: "内嵌页面",
        icon: Monitor,
        tone: "blue",
        description: "仅允许平台内部页面并使用沙箱",
      },
      {
        type: "custom-html",
        label: "自定义内容",
        icon: Edit,
        tone: "red",
        description: "在隔离沙箱中运行网页内容并与页面联动",
      },
      {
        type: "border",
        label: "边框",
        icon: Grid,
        tone: "cyan",
        description: "装饰边框",
      },
      {
        type: "decoration",
        label: "装饰线",
        icon: Sort,
        tone: "amber",
        description: "装饰和分割",
      },
    ],
  },
];

const metaMap = Object.fromEntries(
  componentGroups.flatMap((group) =>
    group.items.map((item) => [item.type, item]),
  ),
);
const chartTypes = new Set([
  "line-chart",
  "bar-chart",
  "custom-chart",
  "pie-chart",
  "ring-chart",
  "gauge",
  "progress",
  "funnel",
  "radar",
  "scatter",
  "area-chart",
  "pictorial-chart",
  "treemap-chart",
  "calendar-chart",
  "word-cloud",
  "bar3d-chart",
]);
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
const mapComponentTypes = new Set([
  "map-chart",
  "map-flow",
  "map-bar",
  "map-heat",
  "map-ranking",
  "map-timeline",
]);

const canvasWidth = computed(() => Number(schema.canvas.width) || 1920);
const canvasHeight = computed(() => Number(schema.canvas.height) || 1080);
const canvasPanEnabled = computed(() => panMode.value || spacePressed.value);
const backgroundModeHint = computed(
  () =>
    backgroundModeOptions.find(
      (item) => item.value === schema.canvas.background?.mode,
    )?.description || backgroundModeOptions[0].description,
);
const selectedId = computed(() => selectedIds.value[0] || "");
const selectedWidget = computed(() =>
  schema.widgets.find((item) => item.id === selectedId.value),
);
const selectedCapabilities = computed(() => dashboardComponentCapabilities(selectedWidget.value));
function selectedPropertyVisible(path) {
  return dashboardPropertyVisible(selectedWidget.value, path);
}
watch(() => [selectedWidget.value?.id, selectedCapabilities.value.data, selectedCapabilities.value.interaction], () => {
  if ((inspectorTab.value === 'data' && !selectedCapabilities.value.data) ||
      (inspectorTab.value === 'interaction' && !selectedCapabilities.value.interaction)) inspectorTab.value = 'style';
});
const batchAllVisible = computed(
  () =>
    selectedIds.value.length > 0 &&
    selectedIds.value.every((id) => widgetById(id)?.state?.visible !== false),
);
const batchAllLocked = computed(
  () =>
    selectedIds.value.length > 0 &&
    selectedIds.value.every((id) => widgetById(id)?.state?.locked === true),
);
const selectedDataset = computed(() =>
  selectedWidget.value?.binding?.sourceType !== "STATIC" &&
  selectedWidget.value?.binding?.datasetCode
    ? datasets.value.find(
        (item) => item.datasetCode === selectedWidget.value.binding.datasetCode,
      )
    : null,
);
const staticFields = computed(() => {
  const rows = selectedWidget.value?.binding?.staticRows;
  const first =
    Array.isArray(rows) && rows.length && rows[0] && typeof rows[0] === "object"
      ? rows[0]
      : {};
  return Object.keys(first).map((name) => ({
    name,
    title: name,
    type: typeof first[name] === "number" ? "number" : "string",
  }));
});
const datasetFields = computed(() =>
  selectedDataset.value
    ? parseArray(selectedDataset.value.fieldSchemaJson)
    : staticFields.value,
);
const datasetParameters = computed(() =>
  parseArray(selectedDataset.value?.paramSchemaJson),
);
const multiSeriesTypes = new Set([
  "line-chart",
  "area-chart",
  "bar-chart",
  "custom-chart",
  "radar",
]);
const fieldRoles = computed(() => {
  if (!selectedWidget.value) return [];
  const type = selectedCapabilities.value.effectiveType;
  if (
    ["metric-card", "number-flip", "gauge", "progress"].includes(
      type,
    )
  )
    return [{ key: "value", label: "数值字段" }];
  if (type === "color-block") return [{ key: "value", label: "颜色字段" }, { key: "label", label: "状态文字字段" }];
  if (type === "weather") return [{ key: "city", label: "城市字段" }, { key: "temperature", label: "温度字段" }, { key: "condition", label: "天气字段" }];
  if (type === "ring-text") return [{ key: "category", label: "文字字段" }];
  if (type === "statistics")
    return [
      { key: "label", label: "统计标题字段", placeholder: "使用手工标题" },
      { key: "value", label: "数值字段" },
      { key: "suffix", label: "数值后缀字段", placeholder: "使用手工后缀" },
      { key: "compareValue", label: "对比值字段" },
      { key: "compareLabel", label: "对比标签字段" },
      { key: "compareState", label: "升降状态字段" },
    ];
  if (type === "access-list")
    return [
      { key: "avatar", label: "头像资源字段" },
      { key: "title", label: "主标题字段" },
      { key: "subtitle", label: "副标题字段" },
      { key: "company", label: "单位字段" },
      { key: "status", label: "状态字段" },
      { key: "time", label: "时间字段" },
    ];
  if (type === "gantt-chart")
    return [
      { key: "id", label: "任务编号字段" }, { key: "task", label: "任务名称字段" },
      { key: "start", label: "计划开始日期字段" }, { key: "end", label: "计划结束日期字段" },
      { key: "actualStart", label: "实际开始日期字段" }, { key: "actualEnd", label: "实际结束日期字段" },
      { key: "progress", label: "完成进度字段" }, { key: "dependencies", label: "依赖任务编号字段" },
      { key: "status", label: "状态字段" },
    ];
  if (type === "milestone-timeline")
    return [
      { key: "label", label: "里程碑名称字段" },
      { key: "startDate", label: "开始日期字段" },
      { key: "endDate", label: "结束日期字段" },
      { key: "status", label: "状态字段" },
    ];
  if (
    ["map-chart", "map-bar", "map-heat", "map-ranking"].includes(
      type,
    )
  )
    return [
      { key: "label", label: "名称字段" },
      { key: "value", label: "数值字段" },
      { key: "longitude", label: "经度字段" },
      { key: "latitude", label: "纬度字段" },
    ];
  if (["map-flow", "map-timeline"].includes(type))
    return [
      { key: "fromName", label: "起点名称字段" },
      { key: "toName", label: "终点名称字段" },
      { key: "fromLongitude", label: "起点经度字段" },
      { key: "fromLatitude", label: "起点纬度字段" },
      { key: "toLongitude", label: "终点经度字段" },
      { key: "toLatitude", label: "终点纬度字段" },
      { key: "value", label: "数值字段" },
      ...(type === "map-timeline"
        ? [{ key: "group", label: "时间分组字段" }]
        : []),
    ];
  if (
    [
      "line-chart",
      "area-chart",
      "bar-chart",
      "custom-chart",
      "pie-chart",
      "ring-chart",
      "pictorial-chart",
      "bar3d-chart",
      "funnel",
      "treemap-chart",
      "word-cloud",
    ].includes(type)
  )
    return [
      { key: "category", label: "分类字段" },
      { key: "value", label: "数值字段" },
    ];
  if (type === "calendar-chart")
    return [
      { key: "category", label: "日期字段" },
      { key: "value", label: "数值字段" },
    ];
  if (["radar", "scatter"].includes(type))
    return [
      { key: "category", label: "维度字段" },
      { key: "value", label: "数值字段" },
    ];
  return [];
});
const numericDatasetFields = computed(() =>
  datasetFields.value.filter(
    (field) => String(field.type || "").toLowerCase() === "number",
  ),
);
const orderedWidgets = computed(() =>
  [...schema.widgets].sort(
    (a, b) => (Number(a.layout?.z) || 0) - (Number(b.layout?.z) || 0),
  ),
);
const holderStyle = computed(() => ({
  width: `${canvasWidth.value * canvasScale.value}px`,
  height: `${canvasHeight.value * canvasScale.value}px`,
}));
const canvasStyle = computed(() => {
  const background = schema.canvas.background || {};
  const layers = [];
  const sizes = [];
  const positions = [];
  const repeats = [];
  if (showGrid.value) {
    const gridColor =
      schema.canvas.theme === "light"
        ? "rgba(55,72,94,.14)"
        : "rgba(255,255,255,.12)";
    layers.push(
      `linear-gradient(${gridColor} 1px, transparent 1px)`,
      `linear-gradient(90deg, ${gridColor} 1px, transparent 1px)`,
    );
    sizes.push("32px 32px", "32px 32px");
    positions.push("0 0", "0 0");
    repeats.push("repeat", "repeat");
  }
  if (background.imageRef) {
    const backgroundLayout = dashboardBackgroundLayout(background);
    layers.push(`url("${dashboardResourceUrl(background.imageRef)}")`);
    sizes.push(backgroundLayout.backgroundSize);
    positions.push(backgroundLayout.backgroundPosition);
    repeats.push(backgroundLayout.backgroundRepeat);
  }
  return {
    width: `${canvasWidth.value}px`,
    height: `${canvasHeight.value}px`,
    transform: `scale(${canvasScale.value})`,
    backgroundColor:
      background.color || schema.canvas.backgroundColor || "#0b1220",
    backgroundImage: layers.length ? layers.join(", ") : undefined,
    backgroundSize: sizes.length ? sizes.join(", ") : undefined,
    backgroundPosition: positions.length ? positions.join(", ") : undefined,
    backgroundRepeat: repeats.length ? repeats.join(", ") : undefined,
  };
});
const canvasWatermarkStyle = computed(() => ({
  fontSize: `${Number(schema.canvas.watermark?.fontSize) || 22}px`,
  color: schema.canvas.watermark?.color || "#d4deeb",
  transform: `rotate(${Number(schema.canvas.watermark?.rotate) || 0}deg)`,
  opacity: Number(schema.canvas.watermark?.opacity ?? 0.12),
}));

function defaultSchema() {
  return {
    schemaVersion: "1.0",
    page: {},
    canvas: {
      width: 1920,
      height: 1080,
      scaleMode: "contain",
      fullscreenScaleMode: "contain",
      hideScrollbar: false,
      theme: "dark",
      palette: "teal",
      paletteColors: paletteOptions[0].colors,
      backgroundColor: "#0b1220",
      background: {
        color: "#0b1220",
        imageRef: "",
        mode: "fill",
        position: "center",
        opacity: 1,
      },
      watermark: {
        enabled: false,
        text: "",
        fontSize: 22,
        color: "#d4deeb",
        rotate: -20,
        opacity: 0.12,
      },
    },
    refresh: { enabled: true, mode: "interval", seconds: 30, at: "08:00" },
    filters: [],
    widgets: [],
  };
}

function normalizeSchema(raw) {
  const input = typeof raw === "string" ? JSON.parse(raw) : raw || {};
  const base = defaultSchema();
  const rawCanvas = input.canvas || {};
  const color =
    rawCanvas.background?.color ||
    rawCanvas.backgroundColor ||
    base.canvas.background.color;
  const backgroundMode = backgroundModeOptions.some(
    (item) => item.value === rawCanvas.background?.mode,
  )
    ? rawCanvas.background.mode
    : base.canvas.background.mode;
  const backgroundPosition = backgroundPositionOptions.some(
    (item) => item.value === rawCanvas.background?.position,
  )
    ? rawCanvas.background.position
    : base.canvas.background.position;
  const normalized = {
    ...base,
    ...input,
    canvas: {
      ...base.canvas,
      ...rawCanvas,
      width: clampInt(rawCanvas.width, 640, 7680, 1920),
      height: clampInt(rawCanvas.height, 360, 4320, 1080),
      scaleMode: ["contain", "stretch"].includes(rawCanvas.scaleMode)
        ? rawCanvas.scaleMode
        : base.canvas.scaleMode,
      fullscreenScaleMode: normalizeFullscreenScaleMode(rawCanvas),
      hideScrollbar: rawCanvas.hideScrollbar === true,
      backgroundColor: color,
      theme: rawCanvas.theme || "dark",
      palette: paletteOptions.some((item) => item.key === rawCanvas.palette)
        ? rawCanvas.palette
        : base.canvas.palette,
      paletteColors:
        Array.isArray(rawCanvas.paletteColors) && rawCanvas.paletteColors.length
          ? rawCanvas.paletteColors.slice(0, 12)
          : base.canvas.paletteColors,
      background: {
        ...base.canvas.background,
        ...(rawCanvas.background || {}),
        color,
        mode: backgroundMode,
        position: backgroundPosition,
      },
      watermark: { ...base.canvas.watermark, ...(rawCanvas.watermark || {}) },
    },
    refresh: {
      ...base.refresh,
      ...(input.refresh || {}),
      mode: input.refresh?.mode === "daily" ? "daily" : "interval",
      seconds: clampInt(input.refresh?.seconds, 5, 3600, base.refresh.seconds),
      at: /^([01]\d|2[0-3]):[0-5]\d$/.test(String(input.refresh?.at || ""))
        ? input.refresh.at
        : base.refresh.at,
    },
    filters: Array.isArray(input.filters)
      ? input.filters.map((item, index) => normalizePageFilter(item, index))
      : [],
    widgets: Array.isArray(input.widgets)
      ? input.widgets.map((item, index) =>
          normalizeWidget(item, index, rawCanvas),
        )
      : [],
  };
  normalized.widgets.forEach((item, index) => {
    item.layout.z = Number(item.layout.z) || index + 1;
  });
  normalized.groups = normalizeDashboardGroups(normalized.widgets, input.groups);
  return normalized;
}

function normalizePageFilter(item, index) {
  const value = item || {};
  const type = String(value.type || "STRING").toUpperCase();
  return {
    id: value.id || `page-filter-${Date.now()}-${index}`,
    label: value.label || value.name || "页面过滤器",
    parameter: value.parameter || value.param || "",
    type: ["STRING", "NUMBER", "DATE", "DATETIME"].includes(type)
      ? type
      : "STRING",
    defaultValue: value.defaultValue ?? value.default ?? "",
    targetWidgetIds: Array.isArray(value.targetWidgetIds)
      ? value.targetWidgetIds
      : [],
  };
}

function normalizeWidget(item, index, rawCanvas) {
  const type = item?.type && metaMap[item.type] ? item.type : "text";
  const meta = metaMap[type];
  const rawLayout = item.layout || {};
  const width = Number(rawCanvas.width) || 1920;
  const height = Number(rawCanvas.height) || 1080;
  const layout = {
    x: clampNumber(rawLayout.x, 0, width - 40, 48 + (index % 3) * 400),
    y: clampNumber(rawLayout.y, 0, height - 40, 48 + Math.floor(index / 3) * 220),
    w: clampNumber(rawLayout.w, 40, width, type === "table" ? 520 : 360),
    h: clampNumber(rawLayout.h, 40, height, type === "table" ? 260 : 160),
    rotate: Number(rawLayout.rotate) || 0,
    z: Number(rawLayout.z) || index + 1,
  };
  layout.x = Math.min(layout.x, Math.max(0, width - layout.w));
  layout.y = Math.min(layout.y, Math.max(0, height - layout.h));
  const binding = {
    sourceType: "DATASET",
    datasetCode: "",
    fieldMap: {},
    parameters: {},
    filters: [],
    displayFields: [],
    rowLimit: 50,
    refreshSeconds: 0,
    staticRows: [],
    ...(item.binding || {}),
  };
  binding.fieldMap =
    binding.fieldMap &&
    typeof binding.fieldMap === "object" &&
    !Array.isArray(binding.fieldMap)
      ? { ...binding.fieldMap }
      : {};
  binding.fieldMap.valueFields = Array.isArray(binding.fieldMap.valueFields)
    ? [...new Set(binding.fieldMap.valueFields.filter(Boolean))]
    : [];
  binding.sourceType = binding.sourceType === "STATIC" ? "STATIC" : "DATASET";
  binding.staticRows = Array.isArray(binding.staticRows)
    ? binding.staticRows
    : [];
  binding.rowLimit = clampInt(binding.rowLimit, 1, 1000, 50);
  binding.displayFields = Array.isArray(binding.displayFields)
    ? binding.displayFields
    : [];
  const interaction = {
    onClick: item.interaction?.clickAction || "none",
    target: "",
    targetMode:
      item.interaction?.targetMode === "published" ||
      item.interaction?.targetPageCode ||
      item.interaction?.targetPageId
        ? "published"
        : "route",
    targetPageCode: "",
    targetPageId: null,
    sourceField: "",
    targetParameter: "",
    targetField: "",
    targetWidgetIds: [],
    parameterMappings: [],
    drilldown: { levels: [] },
    ...(item.interaction || {}),
  };
  interaction.targetWidgetIds = Array.isArray(interaction.targetWidgetIds)
    ? interaction.targetWidgetIds
    : [];
  interaction.targetMode = interaction.targetMode === "published" ? "published" : "route";
  interaction.targetPageCode = String(interaction.targetPageCode || "").trim();
  interaction.targetPageId =
    interaction.targetPageId === null || interaction.targetPageId === undefined || interaction.targetPageId === ""
      ? null
      : Number(interaction.targetPageId) || null;
  interaction.parameterMappingsJson = JSON.stringify(
    Array.isArray(interaction.parameterMappings)
      ? interaction.parameterMappings
      : [],
    null,
    2,
  );
  interaction.drilldown =
    interaction.drilldown && typeof interaction.drilldown === "object"
      ? interaction.drilldown
      : { levels: [] };
  interaction.drilldownJson = JSON.stringify(interaction.drilldown, null, 2);
  const defaultChartConfig = {
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
  const style = {
    title: meta.label,
    titleVisible: true,
    subtitle: "",
    titleAlign: "left",
    titleVerticalAlign: "top",
    titleColor: "#9cabbc",
    titleFontSize: 12,
    titleFontWeight: 600,
    subtitleColor: "#aebccc",
    subtitleFontSize: 10,
    subtitleFontWeight: 400,
    color: "#4fd1b0",
    useSystemPalette: item.style?.useSystemPalette ?? false,
    backgroundColor: "rgba(17,24,39,.78)",
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
    borderTransparent: false,
    text: "",
    unit: "",
    iconName: "star",
    optionJson: "",
    customChartType: "bar-chart",
    bar3dDepth: 8,
    carouselSeconds: 4,
    carouselInterval: 4,
    carouselMode: "card",
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
    timeFormat: "HH:mm:ss",
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
    chartConfig: defaultChartConfig,
    tabs: [
      { key: "overview", label: "概览", content: "概览内容", widgetIds: [] },
      { key: "detail", label: "明细", content: "明细内容", widgetIds: [] },
    ],
    formFields: [
      {
        name: "keyword",
        label: "关键词",
        parameter: "keyword",
        placeholder: "请输入关键词",
      },
    ],
    ...(item.style || {}),
  };
  if (style.title === "自定义 HTML") style.title = "自定义内容";
  style.backgroundTransparent = item.style?.backgroundTransparent === true;
  style.borderTransparent = item.style?.borderTransparent === true;
  style.embeddedMode = item.style?.embeddedMode === true;
  style.titleImageEnabled = item.style?.titleImageEnabled === true;
  style.titleImageRef =
    typeof item.style?.titleImageRef === "string"
      ? item.style.titleImageRef
      : "";
  style.titleImageFit = ["stretch", "contain", "cover"].includes(
    item.style?.titleImageFit,
  )
    ? item.style.titleImageFit
    : "stretch";
  style.titleImageAlign = ["left", "center", "right"].includes(
    item.style?.titleImageAlign,
  )
    ? item.style.titleImageAlign
    : "left";
  style.titleImageHeight = clampInt(item.style?.titleImageHeight, 20, 120, 32);
  style.titlePaddingTop = clampInt(item.style?.titlePaddingTop, 0, 120, 0);
  style.titlePaddingRight = clampInt(
    item.style?.titlePaddingRight,
    0,
    120,
    style.titleImageEnabled ? 8 : 0,
  );
  style.titlePaddingBottom = clampInt(
    item.style?.titlePaddingBottom,
    0,
    120,
    0,
  );
  style.titlePaddingLeft = clampInt(
    item.style?.titlePaddingLeft,
    0,
    120,
    style.titleImageEnabled ? 8 : 0,
  );
  Object.assign(style, normalizeDashboardStatisticsStyle(type, style));
  style.timelineShowDates = item.style?.timelineShowDates !== false;
  style.timelineShowEndDate = item.style?.timelineShowEndDate === true;
  style.timelineLabelPosition = ["above", "below"].includes(
    item.style?.timelineLabelPosition,
  )
    ? item.style.timelineLabelPosition
    : "below";
  style.timelineDateFormat = ["YYYY-MM-DD", "YYYY/MM/DD", "MM-DD"].includes(
    item.style?.timelineDateFormat,
  )
    ? item.style.timelineDateFormat
    : "YYYY-MM-DD";
  style.timelineLayout = ["compact", "spread"].includes(
    item.style?.timelineLayout,
  )
    ? item.style.timelineLayout
    : "compact";
  style.timelineDoneColor = item.style?.timelineDoneColor || "#35d4b0";
  style.timelineActiveColor = item.style?.timelineActiveColor || "#35d4b0";
  style.qualityVisible = dashboardComponentCapabilities({ type }).data && item.style?.qualityVisible !== false;
  style.clockShowIcon = item.style?.clockShowIcon !== false;
  style.chartConfig = {
    ...defaultChartConfig,
    ...(item.style?.chartConfig || {}),
    tooltip: {
      ...defaultChartConfig.tooltip,
      ...(item.style?.chartConfig?.tooltip || {}),
    },
    legend: {
      ...defaultChartConfig.legend,
      ...(item.style?.chartConfig?.legend || {}),
      margin: {
        ...defaultChartConfig.legend.margin,
        ...(item.style?.chartConfig?.legend?.margin || {}),
      },
    },
    xAxis: {
      ...defaultChartConfig.xAxis,
      ...(item.style?.chartConfig?.xAxis || {}),
    },
    yAxis: {
      ...defaultChartConfig.yAxis,
      ...(item.style?.chartConfig?.yAxis || {}),
    },
    grid: {
      ...defaultChartConfig.grid,
      ...(item.style?.chartConfig?.grid || {}),
    },
    label: {
      ...defaultChartConfig.label,
      ...(item.style?.chartConfig?.label || {}),
    },
    markLine: {
      ...defaultChartConfig.markLine,
      ...(item.style?.chartConfig?.markLine || {}),
    },
    center: {
      ...defaultChartConfig.center,
      ...(item.style?.chartConfig?.center || {}),
    },
    colors: Array.isArray(item.style?.chartConfig?.colors)
      ? item.style.chartConfig.colors
      : [],
  };
  style.customChartType = customChartTypes.has(style.customChartType)
    ? style.customChartType
    : "bar-chart";
  style.titleVerticalAlign = ["top", "middle", "bottom"].includes(
    style.titleVerticalAlign,
  )
    ? style.titleVerticalAlign
    : "top";
  style.chartConfig.valueScale = [
    "none",
    "thousand",
    "ten-thousand",
    "million",
  ].includes(style.chartConfig.valueScale)
    ? style.chartConfig.valueScale
    : "none";
  style.chartConfig.valuePrecision = clampInt(
    style.chartConfig.valuePrecision,
    0,
    6,
    2,
  );
  style.chartConfig.xAxis.type = safeAxisType(
    style.chartConfig.xAxis.type,
    "category",
  );
  style.chartConfig.xAxis.axisLineShow =
    style.chartConfig.xAxis.axisLineShow !== false;
  style.chartConfig.yAxis.type = safeAxisType(
    style.chartConfig.yAxis.type,
    "value",
  );
  style.chartConfig.yAxis.axisLineShow =
    style.chartConfig.yAxis.axisLineShow !== false;
  ["left", "right", "top", "bottom"].forEach((edge) => {
    style.chartConfig.legend.margin[edge] = clampInt(
      style.chartConfig.legend.margin[edge],
      0,
      240,
      0,
    );
  });
  style.chartConfig.colorsText = style.chartConfig.colors.join(",");
  style.tabsJson = style.tabsJson || JSON.stringify(style.tabs, null, 2);
  style.formFields = parseArray(style.formFields).map((field, fieldIndex) => {
    const normalized = {
      name: "",
      label: "",
      parameter: "",
      placeholder: "",
      defaultValue: "",
      type: "STRING",
      options: [],
      ...(field || {}),
    };
    normalized.name = normalized.name || `field${fieldIndex + 1}`;
    normalized.label = normalized.label || normalized.name;
    normalized.parameter = normalized.parameter || normalized.name;
    normalized.type = formFieldTypes.some(
      (item) => item.value === String(normalized.type || "").toUpperCase(),
    )
      ? String(normalized.type).toUpperCase()
      : "STRING";
    normalized.options = parseArray(
      normalized.options || normalized.optionsJson,
    );
    normalized.optionsJson = JSON.stringify(normalized.options);
    return normalized;
  });
  style.formFieldsJson =
    style.formFieldsJson || JSON.stringify(style.formFields, null, 2);
  return {
    ...item,
    id: item.id || `${type}-${Date.now()}-${index}`,
    type,
    name: item.name || item.style?.title || meta.label,
    layout,
    state: { visible: true, locked: false, ...(item.state || {}) },
    binding,
    style,
    interaction,
  };
}

function setTableRules(value) {
  try {
    const rules = JSON.parse(value || "[]");
    if (!Array.isArray(rules) || rules.length > 40) throw new Error();
    selectedWidget.value.style.tableRules = rules;
    markDirty();
  } catch { ElMessage.warning("格式规则必须是最多 40 条的 JSON 数组"); }
}

function customHtmlPreview(widget) {
  const fallback =
    '<div style="height:100%;display:flex;align-items:center;justify-content:center;color:#35d4b0;font:600 18px sans-serif">自定义内容组件</div>';
  const raw = String(widget?.style?.htmlContent || fallback);
  const html = raw
    .replace(/<!doctype[^>]*>/gi, "")
    .replace(/<\/?(?:html|head|body)(?:\s[^>]*)?>/gi, "");
  const csp =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: blob: /profile/ /dashboard/assets/ /static/; font-src data: blob: /profile/ /dashboard/assets/ /static/; media-src data: blob: /profile/ /dashboard/assets/ /static/; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">";
  return (
    '<!doctype html><html><head><meta charset="UTF-8">' +
    csp +
    "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}*{box-sizing:border-box}</style></head><body>" +
    html +
    "</body></html>"
  );
}

function clampInt(value, min, max, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.round(Math.max(min, Math.min(max, number)));
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  return Number.isFinite(number)
    ? Math.max(min, Math.min(max, number))
    : fallback;
}

function parseArray(value) {
  if (!value) return [];
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function previewTabs(widget) {
  const value = parseArray(widget.style?.tabs || widget.style?.tabItems);
  return value.length
    ? value
    : [
        { label: "概览", content: "选项卡内容" },
        { label: "明细", content: "明细内容" },
      ];
}

function previewFormFields(widget) {
  const value = parseArray(widget.style?.formFields);
  return value.length
    ? value
    : [{ name: "keyword", label: "关键词" }];
}

function meta(type) {
  return metaMap[type] || { label: "未识别组件", icon: Grid, tone: "slate" };
}
function isChart(type) {
  return chartTypes.has(type);
}
function isMapWidget(type) {
  return mapComponentTypes.has(type);
}
function isPreviewChart(type) {
  return isChart(type) && type !== "word-cloud";
}
function widgetById(id) {
  return schema.widgets.find((item) => item.id === id);
}

function setDesignChartRef(id, element) {
  if (element) designChartElements[id] = element;
  else delete designChartElements[id];
  scheduleDesignChartRender();
}

function previewDatasetParams(widget) {
  const dataset = datasets.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  );
  const defaults = Object.fromEntries(
    parseArray(dataset?.paramSchemaJson)
      .filter(
        (param) => param?.name && param.default != null && param.default !== "",
      )
      .map((param) => [param.name, param.default]),
  );
  const configured = Object.fromEntries(
    Object.entries(widget.binding?.parameters || {}).filter(
      ([, value]) => value !== "" && value !== null && value !== undefined,
    ),
  );
  return { ...defaults, ...configured };
}

function previewRequestKey(widget) {
  if (widget.binding?.sourceType === "STATIC" || !widget.binding?.datasetCode)
    return "";
  return `${widget.binding.datasetCode}:${JSON.stringify([previewDatasetParams(widget), widget.binding.filters || []])}`;
}

function refreshWidgetPreview(widget, force = false) {
  if (
    !widget ||
    widget.binding?.sourceType === "STATIC" ||
    !widget.binding?.datasetCode
  ) {
    if (widget?.id) delete designPreviewStates[widget.id];
    return Promise.resolve();
  }
  const key = previewRequestKey(widget);
  designPreviewStates[widget.id] = {
    ...(designPreviewStates[widget.id] || {}),
    loading: true,
    requestKey: key,
    message: "",
  };
  if (force) designPreviewCache.delete(key);
  let request = designPreviewCache.get(key);
  if (!request) {
    request = previewDashboardWidgetData(
      widget.binding.datasetCode,
      previewDatasetParams(widget),
      widget.binding.filters || [],
    )
      .then((data) => ({
        ...data,
        rows: Array.isArray(data?.rows) ? data.rows : [],
      }))
      .catch((error) => ({
        quality: "SOURCE_ERROR",
        rows: [],
        message: error?.message || "数据预览加载失败",
      }));
    designPreviewCache.set(key, request);
  }
  return request.then((data) => {
    if (
      previewRequestKey(widget) !== key ||
      designPreviewStates[widget.id]?.requestKey !== key
    )
      return;
    designPreviewStates[widget.id] = {
      ...data,
      loading: false,
      requestKey: key,
    };
    scheduleDesignChartRender();
  });
}

function refreshAllWidgetPreviews(force = false) {
  return Promise.all(
    schema.widgets.map((widget) => refreshWidgetPreview(widget, force)),
  );
}

function widgetPreviewSourceRows(widget) {
  if (widget.binding?.sourceType === "STATIC") {
    return Array.isArray(widget.binding?.staticRows) ? widget.binding.staticRows.filter(row => row && typeof row === "object" && !Array.isArray(row)) : [];
  }
  const rows = widget.binding?.datasetCode ? designPreviewStates[widget.id]?.rows : [];
  return Array.isArray(rows) ? rows : [];
}
function widgetPreviewRows(widget) {
  return widgetPreviewSourceRows(widget).slice(0, Number(widget.binding?.rowLimit) || 50);
}

function widgetPreviewStatus(widget) {
  if (widget.binding?.sourceType === "STATIC")
    return widgetPreviewRows(widget).length ? "" : "暂无静态预览数据";
  if (!widget.binding?.datasetCode) return "";
  const state = designPreviewStates[widget.id];
  if (!state || state.loading) return "数据加载中…";
  if (!["SUCCESS", "NO_DATA", "STALE"].includes(state.quality || "SUCCESS"))
    return state.message || "数据预览不可用";
  return widgetPreviewRows(widget).length ? "" : "暂无预览数据";
}

function previewColumns(widget) {
  const rows = widgetPreviewRows(widget);
  const dataset = datasets.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  );
  const schemaFields = parseArray(dataset?.fieldSchemaJson).filter(
    (field) => field?.name && field.visible !== false && field.hidden !== true,
  );
  const fallback = Object.keys(rows[0] || {}).map((name) => ({
    name,
    title: name,
  }));
  const fields = schemaFields.length ? schemaFields : fallback;
  const selected = Array.isArray(widget.binding?.displayFields)
    ? widget.binding.displayFields.filter(Boolean)
    : [];
  const byName = new Map(fields.map((field) => [field.name, field]));
  const ordered = selected.length
    ? selected.map((name) => byName.get(name) || { name, title: name })
    : fields;
  return ordered;
}

function previewField(widget, fieldName) {
  if (!fieldName) return {};
  const dataset = datasets.value.find(
    (item) => item.datasetCode === widget.binding?.datasetCode,
  );
  return (
    parseArray(dataset?.fieldSchemaJson).find(
      (field) => field?.name === fieldName,
    ) || {}
  );
}

function previewTableRows(widget) {
  const rows = [...widgetPreviewRows(widget)];
  if (widget.type === "rank-table") {
    const map = widget.binding?.fieldMap || {};
    const valueKey = map.value || previewColumns(widget)[1]?.name;
    rows.sort(
      (left, right) =>
        (Number(right?.[valueKey]) || 0) - (Number(left?.[valueKey]) || 0),
    );
  }
  if (widget.type === "carousel-table") return dashboardCarouselRows(widgetPreviewSourceRows(widget), Number(widget.binding?.rowLimit) || 50, previewCarouselOffset(widget));
  if (widget.type === "advanced-table" && widget.style?.advancedScroll !== false && rows.length > 6) return dashboardCarouselRows(rows, 6, previewCarouselOffset(widget));
  return rows;
}

function previewValue(value, field = {}) {
  const displayValue = formatDashboardFieldValue(value, field, "—");
  if (typeof displayValue === "object") return JSON.stringify(displayValue);
  return String(displayValue);
}

function previewColorBlockValue(widget) {
  const row = widgetPreviewRows(widget)[0] || {};
  const key = widget.binding?.fieldMap?.value;
  return dashboardColorBlockValue(key ? row[key] : row.color, widget.style);
}
function previewMappedField(widget, role, row = widgetPreviewRows(widget)[0]) {
  if (!row) return "";
  const key = previewMappedFieldKey(widget, role, row);
  return key ? row[key] : undefined;
}

function previewMappedFieldKey(widget, role, row = widgetPreviewRows(widget)[0]) {
  if (!row) return "";
  const mapped = widget.binding?.fieldMap?.[role];
  if (mapped && Object.prototype.hasOwnProperty.call(row, mapped))
    return mapped;
  const aliases = {
    label: ["label", "name", "title"],
    category: ["category", "name", "label"],
    city: ["city", "cityName", "location"],
    condition: ["condition", "weather", "status"],
    temperature: ["temperature", "temp"],
    suffix: ["suffix", "unit"],
    compareValue: ["compareValue", "compare", "ratio"],
    compareLabel: ["compareLabel"],
    compareState: ["compareState", "trend"],
  };
  const alias = (aliases[role] || [role]).find((key) =>
    Object.prototype.hasOwnProperty.call(row, key),
  );
  if (alias) return alias;
  const keys = Object.keys(row);
  if (role === "value" || role === "temperature") {
    const numeric = keys.find((key) => Number.isFinite(Number(row[key])));
    return numeric || keys[1] || keys[0] || "";
  }
  return keys[0] || "";
}

function previewMetric(widget) {
  const key = previewMappedFieldKey(widget, "value");
  return formatDashboardMetricDisplay(previewValue(
    previewMappedField(widget, "value"),
    previewField(widget, key),
  ), widget.style);
}
function previewFlipCharacters(widget) {
  const raw = String(previewMetric(widget) ?? "");
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
function previewWidgetTitle(widget) {
  const title = widget.type === "statistics"
    ? previewStatistic(widget, "label", dashboardTitleText(widget.style?.title))
    : widget.style?.title;
  return dashboardTitleText(title);
}
function previewStatistic(widget, role, fallback = "") {
  const { key, value } = dashboardStatisticsField(widget, widgetPreviewRows(widget)[0] || {}, role);
  if (dashboardTitleText(value) === "") return fallback;
  const text = previewValue(value, key ? previewField(widget, key) : undefined);
  if (role === "label") return dashboardTitleText(text);
  return role === "value" ? formatDashboardMetricDisplay(text, widget.style) : text;
}
function previewStatisticsCompareText(widget) {
  const label = previewStatistic(widget, "compareLabel", "环比");
  const value = previewStatistic(widget, "compareValue", "—");
  const down = previewStatisticsCompareClass(widget) === "compare-down";
  return value === "—" ? `${label} ${value}` : `${label} ${down ? "↓" : "↑"} ${value}`;
}
function previewStatisticsCompareClass(widget) {
  const state = String(previewStatistic(widget, "compareState", ""))
    .trim()
    .toLowerCase();
  return state.includes("down") ||
    state.includes("下降") ||
    state === "decrease"
    ? "compare-down"
    : "compare-up";
}
function previewAccessRows(widget) {
  return widgetPreviewRows(widget).slice(
    0,
    Math.max(1, Math.min(20, Number(widget.binding?.rowLimit) || 3)),
  );
}
function previewAccessValue(widget, row, role) {
  const key = widget.binding?.fieldMap?.[role];
  if (!key) return "";
  const value = row?.[key];
  if (value === null || value === undefined) return "";
  return previewValue(value, previewField(widget, key));
}
function previewAccessStatusSuccess(widget, row) {
  const value = previewAccessValue(widget, row, "status").trim();
  const successText = String(
    widget.style?.accessStatusSuccessText || "进场",
  ).trim();
  return value === successText;
}
function previewAccessStatusText(widget, row) {
  const value = previewAccessValue(widget, row, "status").trim();
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
const previewNow = ref(Date.now());
let previewClockTimer;
function previewCurrentTime(widget) {
  const text = formatDashboardDateTime(previewNow.value);
  const format = widget.style?.timeFormat || "HH:mm:ss";
  if (format === "YYYY-MM-DD") return text.slice(0, 10);
  if (format === "YYYY-MM-DD HH:mm") return text.slice(0, 16);
  if (format === "YYYY-MM-DD HH:mm:ss") return text;
  return format === "HH:mm" ? text.slice(11, 16) : text.slice(11);
}
function previewCarouselOffset(widget) {
  const seconds = Math.max(2, Number(widget.type === "carousel-table" ? widget.style?.carouselSeconds : widget.style?.carouselInterval) || 4);
  return Math.floor(previewNow.value / (seconds * 1000));
}
function previewCarouselRows(widget) {
  return dashboardCarouselRows(widgetPreviewSourceRows(widget), Math.min(6, Number(widget.binding?.rowLimit) || 3), previewCarouselOffset(widget));
}

function previewMilestones(widget) {
  const rows = widgetPreviewRows(widget);
  if (
    !rows.length &&
    !widget.binding?.datasetCode &&
    widget.binding?.sourceType !== "STATIC"
  )
    return [
      {
        label: "登记",
        startDate: previewMilestoneDate(widget, "2026-08-01"),
        endDate: previewMilestoneDate(widget, "2026-08-06"),
        done: true,
      },
      {
        label: "施工",
        startDate: previewMilestoneDate(widget, "2026-08-06"),
        endDate: previewMilestoneDate(widget, "2026-08-10"),
        active: true,
      },
      {
        label: "验收",
        startDate: previewMilestoneDate(widget, "2026-08-15"),
        endDate: "-",
      },
      {
        label: "投用",
        startDate: previewMilestoneDate(widget, "2026-08-28"),
        endDate: "-",
      },
    ];
  const map = widget.binding?.fieldMap || {};
  return rows.slice(0, Number(widget.binding?.rowLimit) || 50).map((row) => {
    const status = String(row[map.status || "status"] || "").toLowerCase();
    return {
      label: previewValue(
        row[map.label || "label"] ?? row[Object.keys(row)[0]],
        previewField(widget, map.label || "label"),
      ),
      startDate: previewMilestoneDate(
        widget,
        row[map.startDate || "startDate"],
      ),
      endDate: previewMilestoneDate(widget, row[map.endDate || "endDate"]),
      done: ["done", "completed", "已完成", "完成"].includes(status.trim()),
      active: ["active", "current", "processing", "进行中", "当前"].includes(status.trim()),
    };
  });
}
function previewMilestoneDate(widget, value) {
  const result = formatDashboardDate(value, value ? String(value) : "");
  if (String(result) === "-") return "-";
  if (widget.style?.timelineDateFormat === "MM-DD") return String(result).slice(5);
  return widget.style?.timelineDateFormat === "YYYY/MM/DD"
    ? String(result).replaceAll("-", "/")
    : result;
}

function previewWeather(widget, role, fallback) {
  const value = previewMappedField(widget, role);
  return value === "" || value === null || value === undefined
    ? fallback
    : previewValue(
        value,
        previewField(
          widget,
          widget.binding?.fieldMap?.[role] || previewMappedFieldKey(widget, role),
        ),
      );
}

function previewWordCloud(widget) {
  const rows = widgetPreviewRows(widget);
  const map = widget.binding?.fieldMap || {};
  const labelKey = map.category || Object.keys(rows[0] || {})[0];
  const valueKey = map.value || Object.keys(rows[0] || {})[1];
  const scores = rows.map((row) => Number(row[valueKey]) || 0);
  const max = Math.max(...scores, 1);
  const min = Math.min(...scores, 0);
  const colors = widget.style?.chartConfig?.colors?.length
    ? widget.style.chartConfig.colors
    : paletteOptions.find((item) => item.key === schema.canvas.palette)?.colors || paletteOptions[0].colors;
  return rows.slice(0, 100).map((row, index) => ({
    label: previewValue(row[labelKey], previewField(widget, labelKey)),
    size: Math.round(14 + (((Number(row[valueKey]) || 0) - min) / Math.max(max - min, 1)) * 28),
    color: colors[index % colors.length],
  }));
}

function previewRingText(widget) {
  const rows = widgetPreviewRows(widget);
  const key = widget.binding?.fieldMap?.label || widget.binding?.fieldMap?.category || Object.keys(rows[0] || {})[0];
  return rows.length
    ? rows.slice(0, 24).map((row) => previewValue(row[key], previewField(widget, key)))
    : ["项目进度", "质量", "安全", "人员"];
}

function previewMapPoints(widget) {
  const rows = widgetPreviewRows(widget);
  const map = widget.binding?.fieldMap || {};
  const labelKey = map.label || map.category || Object.keys(rows[0] || {})[0];
  return rows
    .slice(0, 12)
    .map((row, index) => ({
      label: previewValue(row[labelKey], previewField(widget, labelKey)),
      left: 12 + ((index * 29) % 76),
      top: 16 + ((index * 37) % 68),
    }));
}

function designRows(widget) {
  const map = widget.binding?.fieldMap || {};
  const category = map.category || "name";
  const value = map.value || "value";
  const configuredRows = widgetPreviewRows(widget);
  if (configuredRows.length) return configuredRows;
  if (widget.binding?.sourceType === "STATIC" || widget.binding?.datasetCode)
    return [];
  if (widget.type === "calendar-chart")
    return [
      { [category]: "2026-08-01", [value]: 18 },
      { [category]: "2026-08-02", [value]: 12 },
      { [category]: "2026-08-03", [value]: 26 },
      { [category]: "2026-08-04", [value]: 9 },
      { [category]: "2026-08-05", [value]: 21 },
    ];
  return [
    { [category]: "示例一", [value]: 18 },
    { [category]: "示例二", [value]: 12 },
    { [category]: "示例三", [value]: 26 },
    { [category]: "示例四", [value]: 9 },
  ];
}

function designChartOption(widget) {
  if (widget.type === "custom-chart") {
    const renderType = customChartTypes.has(widget.style?.customChartType)
      ? widget.style.customChartType
      : "bar-chart";
    let custom = {};
    try { custom = JSON.parse(widget.style?.optionJson || "{}"); } catch { /* Invalid input stays in the editor until corrected. */ }
    const base = designChartOption({ ...widget, type: renderType, style: { ...widget.style, optionJson: "" } });
    return mergeDashboardChartOption(base, custom, true);
  }
  const rows = designRows(widget);
  const map = widget.binding?.fieldMap || {};
  const category = map.category || Object.keys(rows[0] || {})[0] || "name";
  const valueFields = designValueFields(widget, rows);
  const value =
    valueFields[0] || map.value || Object.keys(rows[0] || {})[1] || "value";
  const categoryField = previewField(widget, category);
  const labels = rows.map((row) =>
    previewValue(row[category], categoryField),
  );
  const config = widget.style?.chartConfig || {};
  const seriesValues = valueFields.map((field) =>
    rows.map((row) => scaleChartValue(row[field], config)),
  );
  const values =
    seriesValues[0] || rows.map((row) => scaleChartValue(row[value], config));
  const palette =
    Array.isArray(config.colors) && config.colors.length
      ? config.colors
      : paletteOptions.find((item) => item.key === schema.canvas.palette)
          ?.colors || paletteOptions[0].colors;
  const accent =
    config.colors?.length
      ? config.colors[0]
      : widget.style?.useSystemPalette === true
        ? palette[0]
        : widget.style?.color || palette[0];
  const seriesColors = valueFields.map((field, index) =>
    valueFields.length === 1 ? accent : palette[index % palette.length],
  );
  const axisColor = config.xAxis?.axisLineColor || "#526174";
  const yAxisColor = config.yAxis?.axisLineColor || axisColor;
  const textColor = schema.canvas.theme === "light" ? "#526174" : "#a9b8c8";
  const grid = {
    left: Number(config.grid?.left ?? 42),
    right: Number(config.grid?.right ?? 18),
    top: Number(config.grid?.top ?? 24),
    bottom: Number(config.grid?.bottom ?? 30),
    containLabel: true,
  };
  const axisLabel = {
    color: config.xAxis?.labelColor || textColor,
    fontSize: Math.max(
      9,
      Math.min(28, Number(config.xAxis?.labelFontSize) || 11),
    ),
    rotate: Number(config.xAxis?.labelRotate) || 0,
  };
  const yAxisLabel = {
    color: config.yAxis?.labelColor || textColor,
    fontSize: Math.max(
      9,
      Math.min(28, Number(config.yAxis?.labelFontSize) || 11),
    ),
  };
  const xAxisType = safeAxisType(config.xAxis?.type, "category");
  const yAxisType = safeAxisType(config.yAxis?.type, "value");
  const xAxis = {
    type: xAxisType,
    show: config.xAxis?.show !== false,
    name: config.xAxis?.name || undefined,
    data: xAxisType === "category" ? labels : undefined,
    axisLabel,
    axisLine: {
      show: config.xAxis?.axisLineShow !== false,
      lineStyle: { color: axisColor },
    },
    splitLine: {
      show: config.xAxis?.splitLineShow === true,
      lineStyle: { color: config.xAxis?.splitLineColor || "#d7dee8" },
    },
  };
  const yAxis = {
    type: yAxisType,
    show: config.yAxis?.show !== false,
    name: config.yAxis?.name || undefined,
    min: config.yAxis?.min ?? undefined,
    max: config.yAxis?.max ?? undefined,
    axisLabel: yAxisLabel,
    axisLine: {
      show: config.yAxis?.axisLineShow !== false,
      lineStyle: { color: yAxisColor },
    },
    splitLine: {
      show: config.yAxis?.splitLineShow !== false,
      lineStyle: { color: config.yAxis?.splitLineColor || "#d7dee8" },
    },
  };
  const tooltip = {
    show: config.tooltip?.show !== false,
    trigger: "axis",
    textStyle: {
      color: config.tooltip?.textColor || textColor,
      fontSize: Math.max(
        10,
        Math.min(32, Number(config.tooltip?.fontSize) || 12),
      ),
    },
    backgroundColor: config.tooltip?.backgroundColor || undefined,
    borderColor: config.tooltip?.borderColor || undefined,
  };
  const label = {
    show: config.label?.show === true,
    position: config.label?.position || "top",
    color: config.label?.color || textColor,
    fontSize: Math.max(9, Math.min(32, Number(config.label?.fontSize) || 11)),
    fontWeight: Number(config.label?.fontWeight) || 400,
    formatter: safeLabelFormatter(config.label?.format),
  };
  const legendMargin = config.legend?.margin || {};
  const legend = {
    show: config.legend?.show === true,
    orient: config.legend?.orient || "horizontal",
    left:
      config.legend?.position === "right"
        ? "right"
        : config.legend?.position === "left"
          ? "left"
          : "center",
    top:
      config.legend?.position === "top"
        ? "top"
        : config.legend?.position === "bottom"
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
      Math.min(40, Number(config.legend?.itemWidth) || 14),
    ),
    itemHeight: Math.max(
      4,
      Math.min(24, Number(config.legend?.itemHeight) || 8),
    ),
    textStyle: {
      color: config.legend?.textColor || textColor,
      fontSize: Math.max(
        9,
        Math.min(28, Number(config.legend?.fontSize) || 12),
      ),
    },
  };
  if (widget.type === "ring-chart") {
    const legendValues = Object.fromEntries(
      labels.map((name, index) => [
        name,
        { value: values[index] || 0, index },
      ]),
    );
    const suffix = String(widget.style?.ringLegendSuffix || "");
    const columns = Math.round(
      clampNumber(widget.style?.ringLegendColumns, 1, 4, 1),
    );
    legend.width = clampNumber(
      widget.style?.ringLegendWidth,
      80,
      600,
      180,
    );
    legend.left = `${clampNumber(widget.style?.ringLegendLeft, 0, 100, 50)}%`;
    legend.itemGap = clampNumber(
      widget.style?.ringLegendItemGap,
      0,
      40,
      10,
    );
    if (legend.orient === "vertical" && columns > 1)
      legend.height = Math.max(legend.itemHeight, legend.textStyle.fontSize + 2) * Math.ceil(labels.length / columns) + legend.itemGap * Math.max(0, Math.ceil(labels.length / columns) - 1);
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
        unit: {
          color: "#8f9cab",
          fontSize: Math.max(9, legend.textStyle.fontSize - 1),
        },
      };
      labels.forEach((name, index) => {
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
            `${Number(config.center?.x ?? 50)}%`,
            `${Number(config.center?.y ?? 50)}%`,
          ],
          radius:
            widget.type === "ring-chart"
              ? [
                  `${clampNumber(widget.style?.ringInnerRadius, 0, 90, 46)}%`,
                  `${clampNumber(widget.style?.ringOuterRadius, 1, 100, 72)}%`,
                ]
              : "64%",
          label,
          data: labels.map((name, index) => ({
            name,
            value:
              widget.type === "ring-chart" &&
              widget.style?.ringEqualSegments === true
                ? 1
                : values[index],
          })),
        },
      ],
    };
  else if (widget.type === "gauge")
    option = {
      series: [
        {
          type: "gauge",
          progress: { show: true, itemStyle: { color: accent } },
          axisLine: { lineStyle: { color: [[1, "#d7dee8"]] } },
          detail: {
            valueAnimation: false,
            formatter: `{value}${valueUnitSuffix(widget, config)}`,
            color: textColor,
          },
          data: [{ value: values[0] || 0 }],
        },
      ],
    };
  else if (widget.type === "progress")
    option = {
      xAxis: { show: false, max: 100 },
      yAxis: { show: false, type: "category", data: [""] },
      grid: { left: 12, right: 24, top: 18, bottom: 18 },
      series: [
        {
          type: "bar",
          data: [values[0] || 0],
          barWidth: 16,
          showBackground: true,
          backgroundStyle: { color: "#d7dee8", borderRadius: 8 },
          itemStyle: { color: accent, borderRadius: 8 },
          label: {
            show: config.label?.show !== false,
            position: "right",
            formatter: `{c}${valueUnitSuffix(widget, config, "%")}`,
            color: textColor,
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
          data: labels.map((name, index) => ({ name, value: values[index] })),
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
          label: { ...label, show: true, formatter: "{b}\n{c}" },
          upperLabel: { show: false },
          itemStyle: { borderColor: "#0b1220", borderWidth: 2, gapWidth: 2 },
          data: labels.map((name, index) => ({
            name,
            value: values[index],
            itemStyle: { color: palette[index % palette.length] },
          })),
        },
      ],
    };
  else if (widget.type === "calendar-chart") {
    const calendarRows = rows
      .map((row, index) => ({
        date: formatDashboardDate(row[category], ""),
        value: values[index] ?? 0,
      }))
      .filter((item) => /^\d{4}-\d{2}-\d{2}$/.test(item.date));
    const dates = calendarRows.map((item) => item.date);
    const year = dates[0]?.slice(0, 4) || String(new Date().getFullYear());
    option = {
      tooltip: { ...tooltip, trigger: "item" },
      visualMap: {
        show: false,
        min: Math.min(...values, 0),
        max: Math.max(...values, 1),
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
        dayLabel: { color: textColor, firstDay: 1 },
        monthLabel: { color: textColor },
        yearLabel: { show: false },
        splitLine: { show: false },
      },
      series: [
        {
          type: "heatmap",
          coordinateSystem: "calendar",
          data: calendarRows.map((item) => [item.date, item.value]),
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
          `${Number(config.center?.x ?? 50)}%`,
          `${Number(config.center?.y ?? 50)}%`,
        ],
        indicator: labels.map((name, index) => ({
          name,
          max: Math.max(...seriesValues.flat(), 1),
        })),
        axisName: { color: textColor },
      },
      series: seriesValues.map((series, index) => ({
        name: valueFields[index],
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
            scaleChartValue(row[value], config),
          ]),
          itemStyle: { color: accent },
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
          itemStyle: { color: accent },
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
      { offset: 0, color: palette[0] || accent },
      { offset: 1, color: palette[1] || accent },
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
            color: palette[2] || accent,
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
        name: valueFields[index],
        type: widget.type === "bar-chart" ? "bar" : "line",
        data: series,
        smooth: config.smooth !== false,
        stack: config.stack ? "total" : undefined,
        itemStyle: { color: seriesColors[index] },
        label,
        areaStyle:
          widget.type === "area-chart"
            ? {
                color: seriesColors[index],
                opacity: Number(config.areaOpacity ?? 0.2),
              }
            : undefined,
        markLine:
          index === 0 ? safeMarkLine(config.markLine, textColor) : undefined,
      })),
    };
  let custom = {};
  try {
    custom = widget.style?.optionJson
      ? JSON.parse(widget.style.optionJson)
      : {};
  } catch {
    custom = {};
  }
  applyDashboardChartProperties(option, widget, {
    labels,
    values,
    categoryValues: rows.map((row) => row[category]),
    seriesNames: valueFields.map((field) => previewField(widget, field)?.title || field),
    tooltip,
    label,
    legend,
  });
  return mergeDashboardChartOption(option, custom);
}

function designValueFields(widget, rows) {
  const map = widget.binding?.fieldMap || {};
  const configured = Array.isArray(map.valueFields)
    ? map.valueFields.filter(Boolean)
    : [];
  const available = new Set(rows.flatMap((row) => Object.keys(row || {})));
  const valid = configured.filter(
    (field) => available.size === 0 || available.has(field),
  );
  return [
    ...new Set(
      valid.length
        ? valid
        : [map.value || Object.keys(rows[0] || {})[1] || "value"],
    ),
  ];
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

const chartScaleMeta = {
  none: { factor: 1, label: "" },
  thousand: { factor: 1000, label: "千" },
  "ten-thousand": { factor: 10000, label: "万" },
  million: { factor: 1000000, label: "百万" },
};

function chartScale(value, config) {
  return chartScaleMeta[config?.valueScale] || chartScaleMeta.none;
}
function scaleChartValue(value, config) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  const scaled = number / chartScale(value, config).factor;
  const precision = Math.max(
    0,
    Math.min(6, Number(config?.valuePrecision ?? 2)),
  );
  return Number(scaled.toFixed(precision));
}
function valueUnitSuffix(widget, config, fallback = "") {
  return `${widget.style?.unit || fallback}${chartScale(null, config).label}`;
}

function safeMarkLine(value, textColor = "#dce8f5") {
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
      color: textColor,
      fontSize: 10,
    },
    data: [{ yAxis: Number(value.value) }],
  };
}

function disposeDesignCharts() {
  Object.keys(designChartInstances).forEach((id) => {
    designChartInstances[id]?.dispose?.();
    delete designChartInstances[id];
  });
}

function renderDesignCharts() {
  if (designRenderTimer) {
    window.clearTimeout(designRenderTimer);
    designRenderTimer = null;
  }
  const chartWidgets = schema.widgets.filter(
    (widget) => isChart(widget.type) && widget.type !== "word-cloud",
  );
  const activeIds = new Set(chartWidgets.map((widget) => widget.id));
  Object.keys(designChartInstances).forEach((id) => {
    if (!activeIds.has(id)) {
      designChartInstances[id]?.dispose?.();
      delete designChartInstances[id];
    }
  });
  chartWidgets.forEach((widget) => {
    const element = designChartElements[widget.id];
    if (!element) return;
    let chart = designChartInstances[widget.id];
    if (chart && (chart.isDisposed() || chart.getDom() !== element)) {
      chart.dispose();
      chart = null;
    }
    chart ||= echarts.init(element, undefined, { renderer: "canvas" });
    // The editing canvas follows property changes immediately; runtime animation
    // remains controlled by the runtime renderer.
    chart.setOption({ ...designChartOption(widget), animation: false }, true);
    chart.resize();
    designChartInstances[widget.id] = chart;
  });
}

function scheduleDesignChartRender() {
  // Keep the queued frame: continuous color dragging must not postpone rendering.
  if (designRenderTimer) return;
  designRenderTimer = window.setTimeout(() => nextTick(renderDesignCharts), 16);
}

function disposeComponentPreviewChart() {
  componentPreviewChartInstance?.dispose?.();
  componentPreviewChartInstance = null;
}

function renderComponentPreviewChart() {
  disposeComponentPreviewChart();
  const widget = componentPreview.widget;
  const element = componentPreviewChartElement.value;
  if (
    !componentPreview.visible ||
    !widget ||
    !element ||
    !isPreviewChart(widget.type)
  )
    return;
  componentPreviewChartInstance = echarts.init(element, undefined, {
    renderer: "canvas",
  });
  componentPreviewChartInstance.setOption(designChartOption(widget), true);
}

function responseRows(response) {
  const root = response?.data ?? response;
  if (Array.isArray(response?.rows)) return response.rows;
  if (Array.isArray(root)) return root;
  if (Array.isArray(root?.rows)) return root.rows;
  if (Array.isArray(root?.data)) return root.data;
  return [];
}

function isPublishedPageRow(row) {
  if (!row || typeof row !== "object") return false;
  // 旧后端回退列表仍可能包含停用页；与正式 published 接口保持同一
  // 选择语义，避免把不可运行的页面展示为可跳转目标。
  const status = row.status ?? row.pageStatus;
  const deleted = row.isDeleted ?? row.pageDeleted;
  if (String(status ?? "0") === "1" || String(deleted ?? "0") === "1")
    return false;
  if (row.published === true || row.hasPublished === true) return true;
  const version =
    row.version ?? row.versionNo ?? row.currentVersionNo ?? row.revisionId ?? row.currentRevisionId;
  return version !== null && version !== undefined && String(version) !== "";
}

function normalizePublishedPageRow(row) {
  const value = row && typeof row === "object" ? row : {};
  return {
    ...value,
    pageId: value.pageId ?? value.id ?? value.dashboardPageId ?? null,
    pageCode: String(value.pageCode ?? value.code ?? "").trim(),
    pageName: String(value.pageName ?? value.name ?? value.pageCode ?? "").trim(),
    versionNo: value.versionNo ?? value.version ?? value.currentVersionNo ?? null,
    revisionId: value.revisionId ?? value.currentRevisionId ?? null,
  };
}

function publishedPageLabel(row) {
  const page = normalizePublishedPageRow(row);
  const name = page.pageName || page.pageCode || `页面 ${page.pageId || ""}`;
  return page.versionNo === null || page.versionNo === undefined || page.versionNo === ""
    ? `${name}（${page.pageCode || "无编码"}）`
    : `${name}（${page.pageCode || "无编码"} · V${page.versionNo}）`;
}

function loadPublishedPages() {
  const apply = (response, fallback = false) => {
    const rows = responseRows(response)
      .filter((row) => !fallback || isPublishedPageRow(row))
      .map(normalizePublishedPageRow)
      // 运行态和分享态都以页面编码作为稳定路由；没有编码的历史行不能
      // 作为可选项展示，即使仍带有 pageId。
      .filter((row) => row.pageCode);
    publishedPages.value = rows;
    return rows;
  };
  return listPublishedDashboardPages({ pageNum: 1, pageSize: 1000 })
    .then((response) => apply(response))
    .catch(() =>
      listDashboardPages({ pageNum: 1, pageSize: 1000 }).then((response) =>
        apply(response, true),
      ),
    )
    .catch(() => {
      publishedPages.value = [];
      return [];
    });
}

function load() {
  loading.value = true;
  Promise.all([
    getDashboardPage(pageId),
    listDashboardDatasets({ pageNum: 1, pageSize: 1000 }),
    listDashboardDatasetGroups().catch(() => ({ data: [] })),
    listDashboardAssets({ assetType: "IMAGE" }).catch(() => ({ data: [] })),
    listDashboardAssets({ assetType: "VIDEO" }).catch(() => ({ data: [] })),
    listDashboardMaps().catch(() => ({ data: [] })),
    loadPublishedPages(),
  ])
    .then(
      ([pageRes, datasetRes, datasetGroupRes, imageRes, videoRes, mapRes]) => {
      Object.assign(page, pageRes.data || {});
      useTagsViewStore().updateDashboardPageTitle({ path: designerTagPath, pageName: page.pageName, mode: "设计" });
      datasets.value = datasetRes.rows || [];
      datasetGroups.value = datasetGroupRes.data || datasetGroupRes.rows || [];
      imageAssets.value = imageRes.data || imageRes.rows || [];
      videoAssets.value = videoRes.data || videoRes.rows || [];
      mapResources.value = mapRes.data || mapRes.rows || [];
      const draft = (page.revisions || []).find(
        (item) => item.status === "DRAFT",
      );
      const raw =
        draft?.schemaJson ||
        page.draftSchema ||
        page.publishedSchema ||
        page.currentSchema ||
        defaultSchema();
      Object.assign(schema, normalizeSchema(raw));
      lastSchemaSnapshot = JSON.stringify(schema);
      selectedIds.value = [];
      history.value = [];
      future.value = [];
      historyPending.value = null;
      draftDirty.value = false;
      designPreviewCache.clear();
      Object.keys(designPreviewStates).forEach(
        (id) => delete designPreviewStates[id],
      );
      nextTick(() => {
        fitCanvas();
        refreshAllWidgetPreviews().finally(renderDesignCharts);
      });
    })
    .catch(() => ElMessage.error("设计页面加载失败"))
    .finally(() => {
      loading.value = false;
    });
}

function schemaJson() {
  const output = JSON.parse(JSON.stringify(schema));
  output.canvas.backgroundColor =
    output.canvas.background?.color || output.canvas.backgroundColor;
  output.widgets.forEach((widget) => {
    if (widget.style) {
      delete widget.style.tabsJson;
      delete widget.style.formFieldsJson;
      if (Array.isArray(widget.style.formFields)) {
        widget.style.formFields.forEach((field) => {
          if (field && Array.isArray(field.options)) delete field.optionsJson;
        });
      }
      if (widget.style.chartConfig) {
        const colorsText = String(widget.style.chartConfig.colorsText || "");
        widget.style.chartConfig.colors = colorsText
          .split(",")
          .map((color) => color.trim())
          .filter(Boolean)
          .slice(0, 12);
        delete widget.style.chartConfig.colorsText;
      }
    }
    if (widget.interaction) {
      delete widget.interaction.parameterMappingsJson;
      delete widget.interaction.drilldownJson;
    }
    // 只压缩为其他组件补齐的编辑器默认样式。当前组件的显示默认值与
    // 运行态不一定一致；表单字段、选项卡、数据行和映射更不能按下标做差分。
    const defaults = normalizeWidget({ type: widget.type }, 0, output.canvas);
    widget.style = compactDashboardConfig(widget.style, defaults.style, widget.type);
  });
  return JSON.stringify(output);
}

function compactDashboardConfig(value, defaults, type) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return value;
  // 明确不适用的标量默认值才可省略。未识别属性和所有业务数组完整保留，
  // 避免新增属性、字段值 0/空字符串及后端必填项被当成默认值删除。
  const componentDefaults = [
    [/^map/, type.startsWith("map-")],
    [/^stats/, ["statistics", "metric-card", "number-flip"].includes(type)],
    [/^flip/, type === "number-flip"],
    [/^ringText/, type === "ring-text"],
    [/^ring(?:Inner|Outer|Equal|Legend)/, ["pie-chart", "ring-chart", "custom-chart"].includes(type)],
    [/^advanced/, type === "advanced-table"],
    [/^table/, ["table", "advanced-table", "carousel-table", "rank-table", "alert-list", "realtime-list"].includes(type)],
    [/^carousel/, ["carousel", "carousel-table", "advanced-table"].includes(type)],
    [/^timeline/, type === "milestone-timeline"],
    [/^access/, type === "access-list"],
    [/^temperature/, type === "weather"],
    [/^(?:clockShowIcon|timeFormat)$/, type === "current-time"],
    [/^block/, type === "color-block"],
    [/^buttonPadding/, type === "button"],
    [/^image(?:Fit|Position)$/, type === "image"],
    [/^(?:videoRef|posterRef|autoplay|muted|loop|controls)$/, type === "video"],
    [/^iframeRef$/, type === "iframe"],
  ];
  return Object.fromEntries(Object.entries(value).filter(([key, item]) => {
    if (item !== null && typeof item === "object") return true;
    const rule = componentDefaults.find(([pattern]) => pattern.test(key));
    return !rule || rule[1] || !Object.is(item, defaults?.[key]);
  }));
}

function beginHistory() {
  if (!historyPending.value) historyPending.value = JSON.stringify(schema);
}

function endHistory() {
  if (!historyPending.value) {
    draftDirty.value = true;
    return;
  }
  const before = historyPending.value;
  historyPending.value = null;
  const current = JSON.stringify(schema);
  if (before !== current) {
    history.value.push(before);
    if (history.value.length > 60) history.value.shift();
    future.value = [];
    draftDirty.value = true;
  }
  lastSchemaSnapshot = current;
}

function mutate(fn) {
  beginHistory();
  fn();
  endHistory();
}

function markDirty() {
  draftDirty.value = true;
}

function restoreSnapshot(snapshot) {
  Object.assign(schema, normalizeSchema(snapshot));
  lastSchemaSnapshot = JSON.stringify(schema);
  if (memberEditId.value && widgetById(memberEditId.value)?.groupId && selectedIds.value.includes(memberEditId.value)) {
    selectedIds.value = [memberEditId.value];
  } else {
    memberEditId.value = "";
    selectedIds.value = [...new Set(selectedIds.value.map(widgetById).filter(Boolean).flatMap(widgetSelectionIds))];
  }
  draftDirty.value = true;
}

function undo() {
  if (!history.value.length) return;
  const current = JSON.stringify(schema);
  const snapshot = history.value.pop();
  future.value.push(current);
  restoreSnapshot(snapshot);
}

function redo() {
  if (!future.value.length) return;
  const current = JSON.stringify(schema);
  const snapshot = future.value.pop();
  history.value.push(current);
  restoreSnapshot(snapshot);
}

function newId(type) {
  return `${type}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function focusCanvas() {
  // 先触发旧属性控件的失焦提交，再切换选中组件；聚焦不改变画布滚动位置。
  canvasViewport.value?.focus({ preventScroll: true });
}

function addWidget(item, point = null) {
  focusCanvas();
  mutate(() => {
    const count = schema.widgets.length;
    const width = [
      "table",
      "advanced-table",
      "access-list",
      "rank-table",
      "carousel-table",
      "carousel",
    ].includes(item.type)
      ? 560
      : ["filter-form", "designer-form", "online-form"].includes(item.type)
        ? 480
        : 360;
    const height = ["metric-card", "number-flip", "statistics"].includes(
      item.type,
    )
      ? 150
      : ["table", "advanced-table", "access-list", "carousel-table"].includes(item.type)
        ? 280
        : item.type === "carousel"
          ? 220
          : ["filter-form", "designer-form", "online-form"].includes(item.type)
            ? 180
            : 220;
    const widgetX = point ? point.x - width / 2 : 72 + (count % 3) * 390;
    const widgetY = point
      ? point.y - height / 2
      : 72 + Math.floor(count / 3) * 250;
    const widget = normalizeWidget(
      {
        id: newId(item.type),
        type: item.type,
        name: item.label,
        layout: {
          x: Math.min(Math.max(0, widgetX), canvasWidth.value - width),
          y: Math.min(Math.max(0, widgetY), canvasHeight.value - height),
          w: width,
          h: height,
          z: count + 1,
        },
        binding: { datasetCode: "", fieldMap: {}, parameters: {}, filters: [] },
        style: {
          title: item.label,
          ...(item.type === "icon" ? { fontSize: 48 } : {}),
          color: "#4fd1b0",
          useSystemPalette: true,
          backgroundColor: "rgba(17,24,39,.78)",
        },
      },
      count,
      schema.canvas,
    );
    schema.widgets.push(widget);
    reindexZ();
    selectedIds.value = [widget.id];
    inspectorTab.value = "basic";
  });
}

function startPaletteDrag(event, item) {
  paletteDragType.value = item.type;
  event.dataTransfer?.setData("text/plain", item.type);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "copy";
}
function dropPalette(event) {
  const type =
    paletteDragType.value || event.dataTransfer?.getData("text/plain");
  const item = componentGroups
    .flatMap((group) => group.items)
    .find((candidate) => candidate.type === type);
  paletteDragType.value = "";
  if (item) addWidget(item, canvasPoint(event));
}

function widgetSelectionIds(widget) {
  return widget.groupId
    ? schema.widgets.filter(item => item.groupId === widget.groupId).map(item => item.id)
    : [widget.id];
}
function isMultiSelection(event) {
  return multiSelectMode.value || Boolean(event?.shiftKey || event?.ctrlKey || event?.metaKey);
}
function selectAllWidgets() {
  focusCanvas();
  memberEditId.value = "";
  selectedIds.value = [...new Set(schema.widgets.filter(widget => widget.state?.visible !== false).flatMap(widgetSelectionIds))];
}
function selectWidget(widget, event, preserveSelection = false) {
  if (canvasPan.suppressClick) {
    canvasPan.suppressClick = false;
    return;
  }
  focusCanvas();
  closeContextMenu();
  const editingMember = memberEditId.value === widget.id && !isMultiSelection(event);
  if (!editingMember) {
    if (memberEditId.value && isMultiSelection(event)) selectedIds.value = [...new Set(selectedIds.value.map(widgetById).filter(Boolean).flatMap(widgetSelectionIds))];
    memberEditId.value = "";
  }
  const ids = editingMember ? [widget.id] : widgetSelectionIds(widget);
  if (isMultiSelection(event)) {
    const next = new Set(selectedIds.value);
    const remove = ids.every(id => next.has(id));
    ids.forEach(id => remove ? next.delete(id) : next.add(id));
    selectedIds.value = [...next];
  } else if (!preserveSelection || !ids.every(id => selectedIds.value.includes(id))) {
    selectedIds.value = ids;
  }
}

function selectLayerWidget(widget, event) {
  if (!widget.groupId || isMultiSelection(event)) {
    selectWidget(widget, event);
    return;
  }
  focusCanvas();
  closeContextMenu();
  memberEditId.value = widget.id;
  selectedIds.value = [widget.id];
}
function selectLayerGroup(groupId, event) {
  const widget = schema.widgets.find(item => item.groupId === groupId);
  if (!widget) return;
  if (memberEditId.value && isMultiSelection(event)) selectedIds.value = [...new Set(selectedIds.value.map(widgetById).filter(Boolean).flatMap(widgetSelectionIds))];
  memberEditId.value = "";
  selectWidget(widget, event);
}
function groupName(groupId) {
  return schema.groups?.[groupId]?.name || "组件组合";
}
function syncGroupMetadata() {
  schema.groups = normalizeDashboardGroups(schema.widgets, schema.groups);
}
function renameLayerGroup(groupId, name) {
  if (!schema.widgets.some(widget => widget.groupId === groupId)) return;
  const value = String(name || "").trim().slice(0, 100);
  if (!value || value === groupName(groupId)) return;
  mutate(() => {
    syncGroupMetadata();
    schema.groups[groupId].name = value;
  });
}
function toggleLayerGroup(groupId) {
  if (!schema.widgets.some(widget => widget.groupId === groupId)) return;
  mutate(() => {
    syncGroupMetadata();
    schema.groups[groupId].collapsed = !schema.groups[groupId].collapsed;
  });
  if (schema.groups[groupId].collapsed && widgetById(memberEditId.value)?.groupId === groupId) selectLayerGroup(groupId);
}

function openContextMenu(event, widget) {
  if (!selectedIds.value.includes(widget.id)) selectWidget(widget, event);
  contextMenu.widget = widget;
  contextMenu.x = Math.min(event.clientX, window.innerWidth - 190);
  contextMenu.y = Math.min(event.clientY, window.innerHeight - 360);
  contextMenu.visible = true;
}
function startLayerDrag(event, widget, memberOnly = false) {
  layerDragId.value = widget.id;
  layerDragMemberOnly.value = memberOnly;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", widget.id);
  }
}
function finishLayerDrag() {
  layerDragId.value = "";
  layerDragMemberOnly.value = false;
}
function dropLayer(target, event, memberOnly = false) {
  const sourceId =
    layerDragId.value || event?.dataTransfer?.getData("text/plain");
  const sourceMemberOnly = layerDragMemberOnly.value;
  finishLayerDrag();
  if (!sourceId || !target || sourceId === target.id) return;
  const source = widgetById(sourceId);
  if (!source || !sourceMemberOnly && source.groupId && source.groupId === target.groupId) return;
  if (sourceMemberOnly && (!memberOnly || !source.groupId || source.groupId !== target.groupId)) return;
  mutate(() => {
    const list = [...orderedWidgets.value].reverse();
    if (sourceMemberOnly) {
      const members = list.filter(widget => widget.groupId === source.groupId);
      const from = members.findIndex(widget => widget.id === source.id), to = members.findIndex(widget => widget.id === target.id);
      members.splice(to, 0, members.splice(from, 1)[0]);
      let index = 0;
      setLayerOrder(list.map(widget => widget.groupId === source.groupId ? members[index++] : widget).reverse());
      return;
    }
    const sourceIds = new Set(sourceMemberOnly ? [source.id] : widgetSelectionIds(source));
    const targetIds = new Set(sourceMemberOnly ? [target.id] : widgetSelectionIds(target));
    const chosen = list.filter(item => sourceIds.has(item.id));
    const rest = list.filter(item => !sourceIds.has(item.id));
    const sourceIndex = list.findIndex(item => sourceIds.has(item.id));
    const targetIndex = list.findIndex(item => targetIds.has(item.id));
    const insertAt = sourceIndex < targetIndex
      ? rest.findLastIndex(item => targetIds.has(item.id)) + 1
      : rest.findIndex(item => targetIds.has(item.id));
    if (insertAt < 0) return;
    rest.splice(insertAt, 0, ...chosen);
    setLayerOrder(rest.reverse());
  });
}
function closeContextMenu() {
  contextMenu.visible = false;
  contextMenu.widget = null;
}
function runContextCommand(command) {
  const widget = contextMenu.widget;
  closeContextMenu();
  if (!widget) return;
  if (command === "duplicate") duplicateSelected();
  else if (command === "cut") cutWidgets();
  else if (command === "delete") deleteSelected();
  else if (command === "top" || command === "bottom") moveLayer(command);
  else if (command === "group") groupSelected();
  else if (command === "ungroup") ungroupSelected();
  else if (command === "toggle-lock") toggleLocked(widget, memberEditId.value === widget.id);
  else if (command === "toggle-visible") toggleVisible(widget, memberEditId.value === widget.id);
  else if (command === "preview") openComponentPreview(widget);
  else if (command === "clear-linkage") clearLinkage(widget);
}

function canArrangeSelection(direction) {
  const widgets = selectedIds.value.map(widgetById).filter(Boolean);
  const minimum = direction.startsWith("distribute-") ? 3 : 2;
  return !widgets.some(widget => widget.state?.locked) && dashboardLayoutUnitCount(widgets) >= minimum;
}
function applyLayoutChanges(changes) {
  changes.forEach(({ id, x, y }) => {
    const widget = widgetById(id);
    if (widget) Object.assign(widget.layout, { x, y });
  });
}
function alignSelected(direction) {
  if (!canArrangeSelection(direction)) return;
  focusCanvas();
  const changes = arrangeDashboardWidgets(selectedIds.value.map(widgetById).filter(Boolean), direction, {
    width: canvasWidth.value, height: canvasHeight.value,
  });
  if (changes.length) mutate(() => applyLayoutChanges(changes));
}
function selectionTranslation(dx, dy, originals = null) {
  const widgets = selectedIds.value.map(widgetById).filter(Boolean).map(widget => originals
    ? { ...widget, layout: { ...widget.layout, ...originals[widget.id] } }
    : widget);
  const changes = translateDashboardWidgets(widgets, dx, dy, { width: canvasWidth.value, height: canvasHeight.value });
  // 拖回起点或画布边缘时，也要把上一帧位置恢复到起始快照。
  if (originals && !changes.length && !widgets.some(widget => widget.state?.locked)) {
    return widgets.filter(widget => widgetById(widget.id)?.layout.x !== widget.layout.x || widgetById(widget.id)?.layout.y !== widget.layout.y)
      .map(widget => ({ id: widget.id, x: widget.layout.x, y: widget.layout.y }));
  }
  return changes;
}

function clearSelection() {
  closeContextMenu();
  memberEditId.value = "";
  selectedIds.value = [];
}
function focusWidget(widget) {
  selectedIds.value = memberEditId.value === widget.id ? [widget.id] : widgetSelectionIds(widget);
  if (memberEditId.value !== widget.id) memberEditId.value = "";
  inspectorTab.value = "style";
}
function openComponentPreview(widget) {
  componentPreview.widget = JSON.parse(JSON.stringify(widget));
  componentPreview.visible = true;
}
function widgetVisualVariables(style, embedded, accent, imageButton = false) {
  const defaultPaddingY = embedded ? 0 : 13;
  const defaultPaddingX = embedded ? 0 : 14;
  const padding = (value, fallback) =>
    imageButton ? 0 : Math.max(0, Math.min(120, Number(value ?? fallback) || 0));
  return {
    "--widget-padding-top": `${padding(style.contentPaddingTop, defaultPaddingY)}px`,
    "--widget-padding-right": `${padding(style.contentPaddingRight, defaultPaddingX)}px`,
    "--widget-padding-bottom": `${padding(style.contentPaddingBottom, defaultPaddingY)}px`,
    "--widget-padding-left": `${padding(style.contentPaddingLeft, defaultPaddingX)}px`,
    "--widget-content-align-y": flexVerticalAlignValue(
      style.contentVerticalAlign,
    ),
    "--flip-cell-gap": `${clampNumber(style.flipCellGap, 0, 32, 8)}px`,
    "--flip-cell-width": `${clampNumber(style.flipCellWidth, 18, 96, 42)}px`,
    "--flip-cell-height": `${clampNumber(style.flipCellHeight, 24, 120, 58)}px`,
    "--flip-cell-background": style.flipCellBackground || "rgba(28,56,86,.72)",
    "--flip-cell-border": style.flipCellBorderColor || accent,
    "--stats-label-color": style.statsLabelColor || "#9aabbd",
    "--stats-label-size": `${Math.max(9, Math.min(36, Number(style.statsLabelSize) || 11))}px`,
    "--stats-unit-color": style.statsUnitColor || "#9aabbd",
    "--stats-unit-size": `${Math.max(9, Math.min(30, Number(style.statsUnitSize) || 12))}px`,
    "--stats-up": style.statsUpColor || "#35d4b0",
    "--stats-down": style.statsDownColor || "#ef8d8d",
    "--button-padding-x": `${Math.max(0, Math.min(80, Number(style.buttonPaddingX ?? 22) || 0))}px`,
    "--button-padding-y": `${Math.max(0, Math.min(40, Number(style.buttonPaddingY ?? 8) || 0))}px`,
    "--timeline-done": style.timelineDoneColor || accent,
    "--timeline-active": style.timelineActiveColor || accent,
    "--access-avatar-size": `${Math.max(24, Math.min(72, Number(style.accessAvatarSize) || 56))}px`,
    "--access-row-height": `${Math.max(56, Math.min(120, Number(style.accessRowHeight) || 72))}px`,
    "--table-header-color": style.tableHeaderColor || "#8497aa",
    "--table-text-color": style.tableTextColor || "#cbd8e6",
    "--table-value-color": style.tableValueColor || style.tableTextColor || "#cbd8e6",
    "--table-header-background": style.tableHeaderBackground || "transparent",
    "--table-cell-padding": `${clampNumber(style.tableCellPadding, 0, 32, 6)}px`,
    "--table-first-column-width": `${clampNumber(style.tableFirstColumnWidth, 20, 90, 50)}%`,
  };
}
function changeInteractionAction(action) {
  if (!selectedWidget.value) return;
  const interaction = selectedWidget.value.interaction || {};
  const target = String(interaction.target || "");
  if (action === "browser") {
    if (target && !/^https?:\/\//i.test(target)) interaction.target = "";
    // 页面目标只对 page/drilldown 有效；切换到浏览器链接时清掉旧的
    // published 引用，否则服务端会把它误判为非页面动作携带了目标页面。
    interaction.targetMode = "route";
    interaction.targetPageCode = "";
    interaction.targetPageId = null;
  } else if (
    ["page", "drilldown"].includes(action) &&
    target &&
    !target.startsWith("/") &&
    interaction.targetMode !== "published"
  )
    interaction.target = "";
  else if (
    !["page", "drilldown"].includes(action)
  ) {
    interaction.target = "";
    interaction.targetMode = "route";
    interaction.targetPageCode = "";
    interaction.targetPageId = null;
  } else if (["page", "drilldown"].includes(action)) {
    interaction.targetMode = interaction.targetPageCode ? "published" : "route";
  }
  markDirty();
}

function changeInteractionTargetMode(mode) {
  if (!selectedWidget.value) return;
  const interaction = selectedWidget.value.interaction || {};
  interaction.targetMode = mode === "published" ? "published" : "route";
  if (interaction.targetMode === "route") {
    interaction.targetPageCode = "";
    interaction.targetPageId = null;
  } else if (interaction.targetPageCode) {
    const targetPage = publishedPages.value.find(
      (item) => item.pageCode === interaction.targetPageCode,
    );
    if (targetPage) {
      interaction.targetPageId = targetPage.pageId ?? null;
      if (targetPage.pageId)
        interaction.target = `/dashboard/runtime/${encodeURIComponent(String(targetPage.pageId))}`;
    }
  }
  markDirty();
}

function selectPublishedPage(pageCode) {
  if (!selectedWidget.value) return;
  const interaction = selectedWidget.value.interaction || {};
  const code = String(pageCode || "").trim();
  const targetPage = publishedPages.value.find(
    (item) => item.pageCode === code,
  );
  interaction.targetMode = "published";
  interaction.targetPageCode = code;
  interaction.targetPageId = targetPage?.pageId ?? null;
  // 保留旧版运行端可识别的 target，同时让新版运行端优先使用稳定编码。
  interaction.target = targetPage?.pageId
    ? `/dashboard/runtime/${encodeURIComponent(String(targetPage.pageId))}`
    : code
      ? `/dashboard/runtime/code/${encodeURIComponent(code)}`
      : "";
  markDirty();
}
function componentPreviewStyle(widget) {
  const s = widget?.style || {};
  const accent = widgetAccent(widget);
  const transparent =
    s.backgroundTransparent === true || s.embeddedMode === true;
  const borderTransparent =
    s.borderTransparent === true || s.embeddedMode === true;
  const borderWidth = Math.max(0, Math.min(16, Number(s.borderWidth) || 0));
  const borderStyle = borderStyleValue(s.borderStyle);
  const borderColor = borderTransparent
    ? "transparent"
    : borderColorValue(s, accent);
  return {
    ...widgetVisualVariables(s, s.embeddedMode === true, accent, widget.type === "button" && dashboardButtonUsesImage(s)),
    ...(widget.type !== "advanced-table" ? { "--table-text-color": accent, "--table-value-color": accent, "--table-header-color": accent } : {}),
    "--accent": accent,
    "--widget-font-size": (Number(s.fontSize) || 16) + "px",
    "--widget-font-weight": Number(s.fontWeight) || 400,
    "--widget-text-align": textAlignValue(s.textAlign),
    "--widget-align-items": flexAlignValue(s.textAlign),
    "--widget-content-justify": flexAlignValue(s.textAlign),
    "--widget-border-color": borderColor,
    "--widget-border-width": borderWidth + "px",
    "--widget-border-style": borderStyle,
    "--widget-title-height": `${dashboardHeadingHeight(s, hasTitleImage(widget))}px`,
    background: widget.type === "button" || transparent ? "transparent" : s.backgroundColor || "#111a29",
    border:
      widget?.type === "button"
        ? "0 solid transparent"
        : borderWidth + "px " + borderStyle + " " + borderColor,
    borderRadius: widget.type === "button" && dashboardButtonUsesImage(s) ? "0px" : `${s.borderRadius ?? 8}px`,
    boxShadow: ["button", "border"].includes(widget.type) ? "none" : undefined,
    opacity: s.opacity ?? 1,
    fontSize: (Number(s.fontSize) || 16) + "px",
    fontWeight: Number(s.fontWeight) || 400,
    textAlign: textAlignValue(s.textAlign),
  };
}
function clearLinkage(widget) {
  const targets =
    selectedIds.value.length > 1 && selectedIds.value.includes(widget.id)
      ? selectedIds.value
      : [widget.id];
  mutate(() => {
    schema.widgets
      .filter((item) => targets.includes(item.id))
      .forEach((item) => {
        item.interaction = {
          ...(item.interaction || {}),
          onClick: "none",
          clickAction: "none",
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
        };
        if (item.binding) item.binding.filters = [];
      });
    schema.filters.forEach((filter) => {
      filter.targetWidgetIds = (filter.targetWidgetIds || []).filter(
        (id) => !targets.includes(id),
      );
    });
  });
  ElMessage.success("已清空选中组件的联动配置");
}

function widgetStyle(widget) {
  const l = widget.layout || {};
  const s = widget.style || {};
  const accent = widgetAccent(widget);
  const embedded = s.embeddedMode === true;
  const transparent = embedded || s.backgroundTransparent === true;
  const borderTransparent = embedded || s.borderTransparent === true;
  const borderWidth = Math.max(0, Math.min(16, Number(s.borderWidth) || 0));
  const borderStyle = borderStyleValue(s.borderStyle);
  const borderColor = borderTransparent
    ? "transparent"
    : borderColorValue(s, accent);
  return {
    ...widgetVisualVariables(s, embedded, accent, widget.type === "button" && dashboardButtonUsesImage(s)),
    ...(widget.type !== "advanced-table" ? { "--table-text-color": accent, "--table-value-color": accent, "--table-header-color": accent } : {}),
    left: `${l.x}px`,
    top: `${l.y}px`,
    width: `${l.w}px`,
    height: `${l.h}px`,
    zIndex: l.z || 1,
    transform: `rotate(${l.rotate || 0}deg)`,
    opacity: widget.state?.visible === false ? 0.2 : (s.opacity ?? 1),
    background: widget.type === "button" || transparent ? "transparent" : s.backgroundColor || undefined,
    borderRadius: widget.type === "button" && dashboardButtonUsesImage(s) ? "0px" : `${s.borderRadius || 0}px`,
    fontSize: `${Number(s.fontSize) || 16}px`,
    fontWeight: Number(s.fontWeight) || 400,
    textAlign: textAlignValue(s.textAlign),
    border:
      widget.type === "button"
        ? "0 solid transparent"
        : borderWidth + "px " + borderStyle + " " + borderColor,
    "--accent": accent,
    "--widget-font-size": (Number(s.fontSize) || 16) + "px",
    "--widget-font-weight": Number(s.fontWeight) || 400,
    "--widget-text-align": textAlignValue(s.textAlign),
    "--widget-align-items": flexAlignValue(s.textAlign),
    "--widget-content-justify": flexAlignValue(s.textAlign),
    "--widget-border-color": borderColor,
    "--widget-border-width": borderWidth + "px",
    "--widget-border-style": borderStyle,
    "--widget-title-height": `${dashboardHeadingHeight(s, hasTitleImage(widget))}px`,
  };
}

function widgetAccent(widget) {
  const style = widget?.style || {};
  const canvasColors = Array.isArray(schema.canvas?.paletteColors)
    ? schema.canvas.paletteColors
    : [];
  const presetColors =
    paletteOptions.find((item) => item.key === schema.canvas?.palette)?.colors ||
    paletteOptions[0].colors;
  const systemColor = canvasColors[0] || presetColors[0] || "#35d4b0";
  return style.useSystemPalette === true
    ? systemColor
    : style.color || systemColor;
}
function textAlignValue(value) {
  return ["left", "center", "right"].includes(value) ? value : "center";
}
function flexAlignValue(value) {
  return value === "left"
    ? "flex-start"
    : value === "right"
      ? "flex-end"
      : "center";
}
function flexVerticalAlignValue(value) {
  return value === "top"
    ? "flex-start"
    : value === "bottom"
      ? "flex-end"
      : "center";
}
function borderStyleValue(value) {
  return ["solid", "dashed", "dotted", "double"].includes(value)
    ? value
    : "solid";
}
function borderColorValue(style, fallback) {
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

function isWidgetTitleVisible(widget) {
  return dashboardWidgetTitleVisible(widget, previewWidgetTitle(widget));
}
function hasTitleImage(widget) {
  return (
    isWidgetTitleVisible(widget) &&
    widget?.style?.titleImageEnabled === true &&
    Boolean(dashboardTitleText(widget?.style?.titleImageRef))
  );
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
function widgetHeadingStyle(widget) {
  const s = widget.style || {};
  const imageRef = hasTitleImage(widget)
    ? dashboardResourceUrl(s.titleImageRef)
    : "";
  return {
    color: s.titleColor || "#9cabbc",
    fontSize: `${Number(s.titleFontSize) || 12}px`,
    fontWeight: Number(s.titleFontWeight) || 600,
    textAlign: s.titleAlign || "left",
    justifyContent:
      s.titleVerticalAlign === "bottom"
        ? "flex-end"
        : s.titleVerticalAlign === "middle"
          ? "center"
          : "flex-start",
    alignItems:
      s.titleAlign === "right"
        ? "flex-end"
        : s.titleAlign === "center"
          ? "center"
          : "flex-start",
    padding: `${clampInt(s.titlePaddingTop, 0, 120, 0)}px ${clampInt(s.titlePaddingRight, 0, 120, 0)}px ${clampInt(s.titlePaddingBottom, 0, 120, 0)}px ${clampInt(s.titlePaddingLeft, 0, 120, 0)}px`,
    backgroundImage: imageRef ? `url("${imageRef}")` : undefined,
    backgroundSize: imageRef ? titleImageSize(s.titleImageFit) : undefined,
    backgroundPosition: imageRef
      ? titleImagePosition(s.titleImageFit, s.titleImageAlign)
      : undefined,
    backgroundRepeat: imageRef ? "no-repeat" : undefined,
  };
}

function canvasPoint(event) {
  const rect = canvasElement.value?.getBoundingClientRect();
  if (!rect) return { x: 0, y: 0 };
  return {
    x: (event.clientX - rect.left) / canvasScale.value,
    y: (event.clientY - rect.top) / canvasScale.value,
  };
}

function startCanvasPan(event) {
  const useMiddleButton = event.button === 1;
  const usePanTool = event.button === 0 && canvasPanEnabled.value;
  const viewport = canvasViewport.value;
  if ((!useMiddleButton && !usePanTool) || !viewport) return false;
  closeContextMenu();
  canvasPan.active = true;
  canvasPan.startX = event.clientX;
  canvasPan.startY = event.clientY;
  canvasPan.scrollLeft = viewport.scrollLeft;
  canvasPan.scrollTop = viewport.scrollTop;
  canvasPan.moved = false;
  canvasPan.suppressClick = true;
  event.preventDefault();
  return true;
}

function handleCanvasPointerDown(event) {
  if (startCanvasPan(event) || event.button !== 0) return;
  focusCanvas();
  closeContextMenu();
  if (memberEditId.value && isMultiSelection(event)) selectedIds.value = [...new Set(selectedIds.value.map(widgetById).filter(Boolean).flatMap(widgetSelectionIds))];
  memberEditId.value = "";
  const point = canvasPoint(event);
  const x = Math.max(0, Math.min(canvasWidth.value, point.x));
  const y = Math.max(0, Math.min(canvasHeight.value, point.y));
  const initialIds = isMultiSelection(event) ? [...selectedIds.value] : [];
  Object.assign(marquee, { active: true, moved: false, startX: x, startY: y, x, y, initialIds });
  selectedIds.value = [...initialIds];
  event.preventDefault();
}
function updateMarquee(event) {
  const point = canvasPoint(event);
  marquee.x = Math.max(0, Math.min(canvasWidth.value, point.x));
  marquee.y = Math.max(0, Math.min(canvasHeight.value, point.y));
  if (Math.hypot(marquee.x - marquee.startX, marquee.y - marquee.startY) * canvasScale.value < 3 && !marquee.moved) return;
  marquee.moved = true;
  const left = Math.min(marquee.startX, marquee.x), right = Math.max(marquee.startX, marquee.x);
  const top = Math.min(marquee.startY, marquee.y), bottom = Math.max(marquee.startY, marquee.y);
  const hits = schema.widgets.filter(widget => {
    const l = widget.layout;
    return widget.state?.visible !== false && l.x < right && l.x + l.w > left && l.y < bottom && l.y + l.h > top;
  });
  selectedIds.value = [...new Set([...marquee.initialIds, ...hits.flatMap(widgetSelectionIds)])];
}
function handleWidgetPointerDown(event, widget) {
  if (startCanvasPan(event) || event.button !== 0) return;
  startMove(event, widget);
}
function startMove(event, widget) {
  const toggleOnClick = isMultiSelection(event) && widgetSelectionIds(widget).every(id => selectedIds.value.includes(id));
  if (toggleOnClick) {
    focusCanvas();
    closeContextMenu();
  } else {
    selectWidget(widget, event, true);
  }
  event.preventDefault();
  if (!selectedIds.value.includes(widget.id) || selectedIds.value.some(id => widgetById(id)?.state?.locked)) {
    if (toggleOnClick) selectWidget(widget, event);
    return;
  }
  beginHistory();
  const point = canvasPoint(event);
  drag.mode = "move";
  drag.moved = false;
  drag.toggleOnClick = toggleOnClick ? widget : null;
  drag.widget = widget;
  drag.startX = point.x;
  drag.startY = point.y;
  drag.before = historyPending.value;
  drag.originals = Object.fromEntries(selectedIds.value.map(id => {
    const item = widgetById(id);
    return [id, { x: item.layout.x, y: item.layout.y }];
  }));
}

function startResize(event, widget, handle) {
  if (startCanvasPan(event) || event.button !== 0) return;
  if (widget.groupId && memberEditId.value !== widget.id) {
    selectedIds.value = widgetSelectionIds(widget);
    startGroupResize(event, handle);
    return;
  }
  if (widget.state?.locked) return;
  focusCanvas();
  selectedIds.value = [widget.id];
  beginHistory();
  const point = canvasPoint(event);
  drag.mode = "resize";
  drag.toggleOnClick = null;
  drag.widget = widget;
  drag.handle = handle;
  drag.startX = point.x;
  drag.startY = point.y;
  drag.before = historyPending.value;
  drag.originals = { [widget.id]: { ...widget.layout } };
  event.preventDefault();
}

function startGroupResize(event, handle) {
  if (startCanvasPan(event) || event.button !== 0) return;
  const widgets = selectedIds.value.map(widgetById).filter(Boolean);
  if (!widgets.length || !widgets[0].groupId || widgets.some(widget => widget.groupId !== widgets[0].groupId || widget.state?.locked)) return;
  focusCanvas();
  beginHistory();
  const point = canvasPoint(event);
  drag.mode = "group-resize";
  drag.moved = false;
  drag.toggleOnClick = null;
  drag.widget = widgets[0];
  drag.handle = handle;
  drag.startX = point.x;
  drag.startY = point.y;
  drag.originals = Object.fromEntries(widgets.map(widget => [widget.id, { ...widget.layout }]));
  drag.bounds = getDashboardSelectionBounds(widgets);
  event.preventDefault();
}

function handlePointerMove(event) {
  if (marquee.active) {
    updateMarquee(event);
    return;
  }
  if (canvasPan.active) {
    const viewport = canvasViewport.value;
    if (!viewport) return;
    const dx = event.clientX - canvasPan.startX;
    const dy = event.clientY - canvasPan.startY;
    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) canvasPan.moved = true;
    viewport.scrollLeft = canvasPan.scrollLeft - dx;
    viewport.scrollTop = canvasPan.scrollTop - dy;
    event.preventDefault();
    return;
  }
  if (!drag.mode || !drag.widget) return;
  const point = canvasPoint(event);
  const dx = point.x - drag.startX;
  const dy = point.y - drag.startY;
  if (drag.mode === "move") {
    if (!drag.moved && Math.hypot(dx, dy) * canvasScale.value < 3) return;
    drag.moved = true;
    const anchor = drag.originals[drag.widget.id];
    const changes = selectionTranslation(snap(anchor.x + dx) - anchor.x, snap(anchor.y + dy) - anchor.y, drag.originals);
    if (!changes.length) return;
    applyLayoutChanges(changes);
  } else if (drag.mode === "group-resize") {
    const originals = Object.entries(drag.originals).map(([id, layout]) => ({ ...widgetById(id), id, layout }));
    const changes = resizeDashboardGroup(originals, drag.bounds, drag.handle, dx, dy, {
      width: canvasWidth.value, height: canvasHeight.value, snapToGrid: snapToGrid.value,
    });
    changes.forEach(({ id, layout }) => {
      const widget = widgetById(id);
      if (widget) Object.assign(widget.layout, layout);
    });
  } else {
    resizeWidget(
      drag.widget,
      drag.originals[drag.widget.id],
      drag.handle,
      dx,
      dy,
    );
  }
  draftDirty.value = true;
}

function resizeWidget(widget, original, handle, dx, dy) {
  if (!original) return;
  let x = original.x;
  let y = original.y;
  let w = original.w;
  let h = original.h;
  if (handle.includes("e")) w = original.w + dx;
  if (handle.includes("s")) h = original.h + dy;
  if (handle.includes("w")) {
    x = original.x + dx;
    w = original.w - dx;
  }
  if (handle.includes("n")) {
    y = original.y + dy;
    h = original.h - dy;
  }
  w = Math.max(40, Math.min(canvasWidth.value, w));
  h = Math.max(40, Math.min(canvasHeight.value, h));
  x = Math.max(0, Math.min(canvasWidth.value - w, x));
  y = Math.max(0, Math.min(canvasHeight.value - h, y));
  widget.layout.x = snap(Math.round(x));
  widget.layout.y = snap(Math.round(y));
  widget.layout.w = snap(Math.round(w));
  widget.layout.h = snap(Math.round(h));
}

function finishPointer(event) {
  if (marquee.active) {
    marquee.active = false;
    return;
  }
  if (canvasPan.active) {
    canvasPan.active = false;
    canvasPan.moved = false;
    window.setTimeout(() => {
      canvasPan.suppressClick = false;
    }, 0);
    return;
  }
  if (!drag.mode) return;
  const toggleOnClick = !drag.moved ? drag.toggleOnClick : null;
  drag.mode = "";
  drag.widget = null;
  drag.handle = "";
  drag.originals = {};
  drag.bounds = null;
  drag.toggleOnClick = null;
  endHistory();
  if (toggleOnClick && (!event || event.type === "pointerup")) {
    const widget = widgetById(toggleOnClick.id);
    if (widget && selectedIds.value.includes(widget.id)) selectWidget(widget, { shiftKey: true });
  }
}

function snap(value) {
  return snapToGrid.value ? Math.round(value / 8) * 8 : Math.round(value);
}

function updateSliderValue(mutator) {
  if (!historyPending.value) beginHistory();
  mutator();
  draftDirty.value = true;
}
function finishSliderChange() {
  if (historyPending.value) endHistory();
}
function updateLayoutSlider(key, value) {
  updateSliderValue(() => {
    if (selectedWidget.value)
      selectedWidget.value.layout[key] = Number(value) || 0;
  });
}
function updateStyleSlider(key, value) {
  updateSliderValue(() => {
    if (selectedWidget.value) selectedWidget.value.style[key] = value;
  });
}
function updateStyleColor(key, value) {
  if (value === null || value === undefined) return;
  updateSliderValue(() => {
    if (!selectedWidget.value) return;
    setColorValue(selectedWidget.value.style, key, value);
    if (key === "color")
      selectedWidget.value.style.useSystemPalette = false;
  });
}
function finishStyleColor(key, value) {
  if (!historyPending.value) beginHistory();
  if (selectedWidget.value) {
    setColorValue(selectedWidget.value.style, key, value);
    if (key === "color")
      selectedWidget.value.style.useSystemPalette = false;
  }
  draftDirty.value = true;
  if (historyPending.value) endHistory();
}
// Paths are fixed inspector bindings, including nested chart and canvas colors.
function setColorValue(target, path, value) {
  const keys = path.split(".");
  const key = keys.pop();
  const owner = keys.reduce((value, key) => value?.[key], target);
  if (owner) owner[key] = value || "";
}
function updateCanvasColor(path, value) {
  if (value === null || value === undefined) return;
  updateSliderValue(() => setColorValue(schema.canvas, path, value));
}
function finishCanvasColor(path, value) {
  updateCanvasColor(path, value || "");
  finishSliderChange();
}
function updateBatchStyleColor(key, value) {
  if (value === null || value === undefined) return;
  updateSliderValue(() => {
    batchStyle[key] = value;
    selectedIds.value.map(widgetById).filter(Boolean).forEach((widget) => {
      setColorValue(widget.style, key, value);
      if (key === "color") widget.style.useSystemPalette = false;
    });
  });
}
function finishBatchStyleColor(key, value) {
  updateBatchStyleColor(key, value || "");
  finishSliderChange();
}
function updateBatchStyleSlider(key, value) {
  updateSliderValue(() => {
    batchStyle[key] = value;
    selectedIds.value
      .map(widgetById)
      .filter(Boolean)
      .forEach((widget) => {
        widget.style = { ...(widget.style || {}), [key]: value };
      });
  });
}
function updateWatermarkSlider(key, value) {
  updateSliderValue(() => {
    schema.canvas.watermark[key] = value;
  });
}
function updateChartAreaOpacity(value) {
  updateSliderValue(() => {
    if (selectedWidget.value?.style?.chartConfig)
      selectedWidget.value.style.chartConfig.areaOpacity = Number(value);
  });
}

function setLayout(key, value) {
  beginHistory();
  const widget = selectedWidget.value;
  if (!widget) return;
  widget.layout[key] = Number(value) || 0;
  if (["x", "y", "w", "h"].includes(key)) {
    widget.layout.x = Math.max(
      0,
      Math.min(canvasWidth.value - widget.layout.w, widget.layout.x),
    );
    widget.layout.y = Math.max(
      0,
      Math.min(canvasHeight.value - widget.layout.h, widget.layout.y),
    );
  }
  endHistory();
}
function setStyle(key, value) {
  beginHistory();
  if (selectedWidget.value) {
    selectedWidget.value.style[key] = value;
    if (key === "color") selectedWidget.value.style.useSystemPalette = false;
  }
  endHistory();
}
function isRegisteredImageResource(value) {
  return (
    !value || imageAssets.value.some((asset) => asset.resourcePath === value)
  );
}
function iconSelectionLabel(widget) {
  const style = widget?.style || {};
  return dashboardIconUsesImage(style)
    ? dashboardIconImageLabel(style.imageRef, imageAssets.value)
    : dashboardIconOptions.find(item => item.value === style.iconName)?.label || "星标";
}
function openIconPicker() {
  if (selectedWidget.value?.type !== "icon") return;
  iconPickerTargetId.value = selectedWidget.value.id;
  iconPickerVisible.value = true;
  loadIconAssets();
}
async function loadIconAssets() {
  if (iconAssetsLoading.value) return;
  iconAssetsLoading.value = true;
  try {
    const response = await listDashboardAssets({ assetType: "IMAGE" });
    imageAssets.value = response.data || response.rows || [];
  } catch {
    // 请求封装显示错误；保留已加载的资源，内置图标仍可选择。
  } finally {
    iconAssetsLoading.value = false;
  }
}
function applyIconSelection(choice) {
  const widget = widgetById(iconPickerTargetId.value);
  if (widget?.type !== "icon") return;
  if (choice.kind === "image" && choice.imageRef === widget.style.imageRef) return;
  if (choice.kind === "builtin") {
    if (!dashboardIconOptions.some(item => item.value === choice.iconName)) return;
    mutate(() => {
      widget.style.iconName = choice.iconName;
      widget.style.imageRef = "";
    });
  } else if (choice.kind === "image") {
    const registered = imageAssets.value.some(item => item.resourcePath === choice.imageRef)
      || dashboardLibraryImages.some(item => item.resourcePath === choice.imageRef);
    if (!registered || !dashboardIconImageUrl(choice.imageRef, dashboardResourceUrl)) {
      ElMessage.warning("请选择图标库中可用的图片资源");
      return;
    }
    mutate(() => { widget.style.imageRef = choice.imageRef; });
  }
}
function setImageResource(value) {
  if (!isRegisteredImageResource(value)) {
    ElMessage.warning("请选择图库中已登记的图片资源");
    return;
  }
  setStyle("imageRef", value || "");
}
function setTitleImageResource(value) {
  if (!isRegisteredImageResource(value)) {
    ElMessage.warning("请选择图库中已登记的图片资源");
    return;
  }
  setStyle("titleImageRef", value || "");
}
function setTitleImageEnabled(value) {
  beginHistory();
  const style = selectedWidget.value?.style;
  if (style) {
    style.titleImageEnabled = Boolean(value);
    if (
      value &&
      selectedWidget.value?.type !== "button" &&
      !Number(style.titlePaddingLeft) &&
      !Number(style.titlePaddingRight)
    ) {
      style.titlePaddingLeft = 8;
      style.titlePaddingRight = 8;
    }
  }
  endHistory();
}
function applyChartColors(value) {
  const colors = String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 12);
  mutate(() => {
    if (selectedWidget.value?.style?.chartConfig)
      selectedWidget.value.style.chartConfig.colors = colors;
  });
}
function setWidgetRefresh(value) {
  beginHistory();
  const seconds = Math.max(0, Math.min(3600, Math.round(Number(value) || 0)));
  if (selectedWidget.value)
    selectedWidget.value.binding.refreshSeconds = seconds > 0 ? Math.max(5, seconds) : 0;
  endHistory();
}
function applyInteractionMappings(value) {
  try {
    const parsed = JSON.parse(value);
    if (!Array.isArray(parsed) || parsed.length > 20)
      throw new Error("invalid");
    mutate(() => {
      if (selectedWidget.value)
        selectedWidget.value.interaction.parameterMappings = parsed
          .map((item) => ({
            sourceField: String(item.sourceField || ""),
            targetParameter: String(item.targetParameter || ""),
          }))
          .filter((item) => item.sourceField && item.targetParameter);
    });
  } catch {
    ElMessage.warning(
      "钻取映射必须是 JSON 数组，且每项包含 sourceField 与 targetParameter",
    );
  }
}
function applyDrilldownJson(value) {
  try {
    const parsed = JSON.parse(value);
    if (
      !parsed ||
      typeof parsed !== "object" ||
      Array.isArray(parsed) ||
      !Array.isArray(parsed.levels) ||
      parsed.levels.length < 1 ||
      parsed.levels.length > 5
    )
      throw new Error("invalid");
    mutate(() => {
      if (selectedWidget.value)
        selectedWidget.value.interaction.drilldown = { levels: parsed.levels };
    });
  } catch {
    ElMessage.warning("组件逐级钻取配置必须是包含 1-5 个 levels 的 JSON 对象");
  }
}
function syncBatchStyle() {
  const widget = widgetById(selectedIds.value[0]);
  if (!widget) return;
  batchStyle.color = widget.style?.color || "#35d4b0";
  batchStyle.backgroundColor =
    widget.style?.backgroundColor || "rgba(17,24,39,.78)";
  batchStyle.opacity = Number(widget.style?.opacity ?? 1);
  batchStyle.borderRadius = Number(widget.style?.borderRadius || 0);
}
function applyBatchStyle(key, value) {
  if (!selectedIds.value.length) return;
  mutate(() =>
    selectedIds.value
      .map(widgetById)
      .filter(Boolean)
      .forEach((widget) => {
        widget.style = { ...(widget.style || {}), [key]: value };
      }),
  );
}
function applyBatchState(key, value) {
  if (!selectedIds.value.length) return;
  mutate(() =>
    selectedIds.value
      .map(widgetById)
      .filter(Boolean)
      .forEach((widget) => {
        widget.state = { ...(widget.state || {}), [key]: Boolean(value) };
      }),
  );
}
function syncTabsJson(widget = selectedWidget.value) {
  if (widget?.style)
    widget.style.tabsJson = JSON.stringify(
      Array.isArray(widget.style.tabs) ? widget.style.tabs : [],
      null,
      2,
    );
}
function syncTabsJsonAndHistory() {
  syncTabsJson();
  endHistory();
}
function addTab() {
  mutate(() => {
    const widget = selectedWidget.value;
    if (!widget) return;
    const tabs = Array.isArray(widget.style.tabs)
      ? widget.style.tabs
      : (widget.style.tabs = []);
    tabs.push({
      key: newId("tab"),
      label: `选项 ${tabs.length + 1}`,
      content: "",
      widgetIds: [],
    });
    syncTabsJson(widget);
  });
}
function removeTab(index) {
  mutate(() => {
    const widget = selectedWidget.value;
    const tabs = widget?.style?.tabs;
    if (!Array.isArray(tabs) || tabs.length <= 1) return;
    tabs.splice(index, 1);
    syncTabsJson(widget);
  });
}
function applyTabsJson(value) {
  try {
    const parsed = JSON.parse(value);
    if (!Array.isArray(parsed) || !parsed.length) throw new Error("empty");
    mutate(() => {
      if (selectedWidget.value) {
        selectedWidget.value.style.tabs = parsed.map((item, index) => ({
          key: item.key || `tab-${index}`,
          label: String(item.label || `选项 ${index + 1}`),
          content: String(item.content || ""),
          widgetIds: Array.isArray(item.widgetIds)
            ? item.widgetIds.filter((id) =>
                schema.widgets.some((widget) => widget.id === id),
              )
            : [],
        }));
        syncTabsJson(selectedWidget.value);
      }
    });
  } catch {
    ElMessage.warning("选项卡配置必须是 JSON 数组");
  }
}
function applyFormFieldsJson(value) {
  try {
    const parsed = JSON.parse(value);
    if (!Array.isArray(parsed) || !parsed.length) throw new Error("empty");
    mutate(() => {
      if (selectedWidget.value)
        selectedWidget.value.style.formFields = parsed.map((item, index) => ({
          name: String(item.name || `field${index + 1}`),
          label: String(item.label || item.name || `字段 ${index + 1}`),
          parameter: String(item.parameter || item.name || ""),
          placeholder: String(item.placeholder || ""),
          defaultValue: item.defaultValue ?? "",
          type: formFieldTypes.some(
            (option) => option.value === String(item.type || "").toUpperCase(),
          )
            ? String(item.type).toUpperCase()
            : "STRING",
          options: parseArray(item.options || item.optionsJson),
          optionsJson: JSON.stringify(
            parseArray(item.options || item.optionsJson),
          ),
        }));
    });
  } catch {
    ElMessage.warning("查询字段配置必须是 JSON 数组");
  }
}
function addFormField() {
  mutate(() => {
    if (!selectedWidget.value) return;
    const index = selectedWidget.value.style.formFields.length;
    selectedWidget.value.style.formFields.push({
      name: `field${index + 1}`,
      label: `字段 ${index + 1}`,
      parameter: schema.filters[0]?.parameter || "",
      placeholder: "请输入",
      defaultValue: "",
      type: "STRING",
      options: [],
      optionsJson: "[]",
    });
  });
}
function removeFormField(index) {
  mutate(() => {
    if (
      selectedWidget.value &&
      selectedWidget.value.style.formFields.length > 1
    )
      selectedWidget.value.style.formFields.splice(index, 1);
  });
}
function applyFormFieldOptions(field) {
  try {
    const parsed = JSON.parse(field.optionsJson || "[]");
    if (!Array.isArray(parsed) || parsed.length > 100)
      throw new Error("invalid");
    mutate(() => {
      field.options = parsed
        .map((item) =>
          item && typeof item === "object"
            ? {
                label: String(item.label ?? item.value ?? ""),
                value: item.value ?? "",
              }
            : { label: String(item ?? ""), value: item ?? "" },
        )
        .filter((item) => item.label && item.value !== "");
    });
    field.optionsJson = JSON.stringify(field.options);
  } catch {
    ElMessage.warning(
      '选项必须是 JSON 数组，例如 [{"label":"正常","value":"1"}]',
    );
  }
}
function setCanvas(key, value) {
  beginHistory();
  schema.canvas[key] = Number(value) || schema.canvas[key];
  schema.widgets.forEach((widget) => {
    widget.layout.x = Math.min(
      widget.layout.x,
      canvasWidth.value - widget.layout.w,
    );
    widget.layout.y = Math.min(
      widget.layout.y,
      canvasHeight.value - widget.layout.h,
    );
  });
  endHistory();
  nextTick(fitCanvas);
}

function applyPalette(key) {
  const palette = paletteOptions.find((item) => item.key === key);
  if (!palette) return;
  mutate(() => {
    schema.canvas.palette = palette.key;
    schema.canvas.paletteColors = [...palette.colors];
  });
}

function setBackgroundImage(value) {
  mutate(() => {
    schema.canvas.background.imageRef = value || "";
  });
}

function beforeBackgroundUpload(file) {
  const allowed = ["image/png", "image/jpeg", "image/webp", "image/gif"];
  if (!allowed.includes(file.type)) {
    ElMessage.warning("背景图仅支持 PNG、JPG、WEBP 或 GIF");
    return false;
  }
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning("背景图大小不能超过 8 MB");
    return false;
  }
  return true;
}

function handleBackgroundUpload(response) {
  if (response?.code !== 200 || !response.fileName) {
    ElMessage.error(response?.msg || "背景图上传失败");
    return;
  }
  mutate(() => {
    schema.canvas.background.imageRef = response.fileName;
  });
  ElMessage.success("背景图已上传并应用");
}

function handleBackgroundUploadError() {
  ElMessage.error("背景图上传失败，请检查登录状态和文件大小");
}

function beginPageNameEdit() {
  if (savingPageName.value) return;
  pageNameDraft.value = String(page.pageName || "");
  pageNameEditing.value = true;
  nextTick(() => pageNameInput.value?.focus?.());
}

function cancelPageNameEdit() {
  pageNameEditing.value = false;
  pageNameDraft.value = "";
}

function commitPageName() {
  if (!pageNameEditing.value || savingPageName.value) return;
  const value = pageNameDraft.value.trim();
  if (!value) {
    ElMessage.warning("页面名称不能为空");
    nextTick(() => pageNameInput.value?.focus?.());
    return;
  }
  if (value === page.pageName) {
    cancelPageNameEdit();
    return;
  }
  savingPageName.value = true;
  updateDashboardPage({ pageId, pageName: value })
    .then(() => {
      page.pageName = value;
      useTagsViewStore().updateDashboardPageTitle({ path: designerTagPath, pageName: value, mode: "设计" });
      cancelPageNameEdit();
      ElMessage.success("页面名称已更新");
    })
    .finally(() => {
      savingPageName.value = false;
    });
}

function staticRowsText(widget) {
  return JSON.stringify(
    Array.isArray(widget?.binding?.staticRows) &&
      widget.binding.staticRows.length
      ? widget.binding.staticRows
      : [
          { name: "示例一", value: 18 },
          { name: "示例二", value: 12 },
        ],
    null,
    2,
  );
}
function syncStaticRowsEditor() {
  const widget = selectedWidget.value;
  staticRowsEditorText.value =
    widget?.binding?.sourceType === "STATIC" ? staticRowsText(widget) : "";
}
function setWidgetSource(sourceType) {
  mutate(() => {
    if (!selectedWidget.value) return;
    const binding = selectedWidget.value.binding;
    if (sourceType === "STATIC") {
      binding.sourceType = "STATIC";
      binding.datasetCode = "";
      binding.parameters = {};
      binding.filters = [];
      if (!Array.isArray(binding.staticRows) || !binding.staticRows.length)
        binding.staticRows = [
          { name: "示例一", value: 18 },
          { name: "示例二", value: 12 },
        ];
    } else {
      binding.sourceType = "DATASET";
      binding.staticRows = [];
    }
  });
  syncStaticRowsEditor();
}
function setStaticRowsJson(value) {
  try {
    // Element Plus 的 change 事件传入文本，blur 事件传入原生事件对象；
    // 两种入口统一读取当前草稿，避免合法 JSON 在失焦时被误判。
    const raw = typeof value === "string" ? value : staticRowsEditorText.value;
    const rows = JSON.parse(raw);
    if (!Array.isArray(rows)) throw new Error("not-array");
    mutate(() => {
      if (selectedWidget.value) {
        selectedWidget.value.binding.sourceType = "STATIC";
        selectedWidget.value.binding.datasetCode = "";
        selectedWidget.value.binding.staticRows = rows;
      }
    });
    staticRowsEditorText.value = staticRowsText(selectedWidget.value);
  } catch {
    ElMessage.warning("静态数据必须是 JSON 数组");
  }
}
function setDataset(code) {
  mutate(() => {
    if (!selectedWidget.value) return;
    selectedWidget.value.binding.sourceType = "DATASET";
    selectedWidget.value.binding.datasetCode = code || "";
    selectedWidget.value.binding.staticRows = [];
    selectedWidget.value.binding.fieldMap = {};
  });
  syncStaticRowsEditor();
}
function setWidgetRowLimit(value) {
  beginHistory();
  if (selectedWidget.value)
    selectedWidget.value.binding.rowLimit = Math.max(
      1,
      Math.min(1000, Number(value) || 50),
    );
  endHistory();
}
function setFieldMap(key, value) {
  beginHistory();
  if (selectedWidget.value) {
    const nextValue = Array.isArray(value)
      ? [...new Set(value.filter(Boolean))]
      : value || "";
    selectedWidget.value.binding.fieldMap = {
      ...(selectedWidget.value.binding.fieldMap || {}),
      [key]: nextValue,
    };
  }
  endHistory();
}
function supportsMultiSeries(type) {
  return multiSeriesTypes.has(type);
}
function setParameter(key, value) {
  beginHistory();
  if (selectedWidget.value) {
    selectedWidget.value.binding.parameters = {
      ...(selectedWidget.value.binding.parameters || {}),
      [key]: value,
    };
  }
  endHistory();
}
function addFilter() {
  mutate(() => {
    if (selectedWidget.value) {
      selectedWidget.value.binding.filters = [
        ...(selectedWidget.value.binding.filters || []),
        { id: newId("filter"), field: "", operator: "eq", value: "" },
      ];
    }
  });
}
function removeFilter(index) {
  mutate(() => {
    if (selectedWidget.value)
      selectedWidget.value.binding.filters.splice(index, 1);
  });
}
function addPageFilter() {
  mutate(() => {
    schema.filters.push({
      id: newId("page-filter"),
      label: "页面过滤器",
      parameter: "",
      type: "STRING",
      defaultValue: "",
      targetWidgetIds: [],
    });
  });
}
function removePageFilter(index) {
  mutate(() => {
    schema.filters.splice(index, 1);
  });
}

function duplicateSelected() {
  if (!selectedIds.value.length) return;
  mutate(() => {
    const groups = new Map();
    selectedIds.value
      .map(widgetById)
      .filter(Boolean)
      .forEach((widget) => {
        if (widget.groupId && memberEditId.value !== widget.id && !groups.has(widget.groupId) && widgetSelectionIds(widget).every(id => selectedIds.value.includes(id)))
          groups.set(widget.groupId, newId("group"));
      });
    const clones = translateDashboardClones(selectedIds.value
      .map((id) => cloneWidget(widgetById(id), 0, groups))
      .filter(Boolean), 24, { width: canvasWidth.value, height: canvasHeight.value });
    schema.widgets.push(...clones);
    schema.groups = { ...schema.groups, ...copyDashboardGroupMetadata(schema.groups, groups) };
    syncGroupMetadata();
    reindexZ();
    memberEditId.value = "";
    selectedIds.value = clones.map((item) => item.id);
  });
}
function cloneWidget(widget, offset = 0, groups = new Map()) {
  if (!widget) return null;
  const clone = JSON.parse(JSON.stringify(widget));
  clone.id = newId(widget.type);
  clone.name = `${widget.name || meta(widget.type).label} 副本`;
  clone.layout.x = offset === 0 ? clone.layout.x : Math.min(
    canvasWidth.value - clone.layout.w,
    clone.layout.x + offset,
  );
  clone.layout.y = offset === 0 ? clone.layout.y : Math.min(
    canvasHeight.value - clone.layout.h,
    clone.layout.y + offset,
  );
  if (widget.groupId && groups.has(widget.groupId))
    clone.groupId = groups.get(widget.groupId);
  else delete clone.groupId;
  return clone;
}
function deleteWidget(widget) {
  if (!widget) return;
  const ids = new Set(memberEditId.value === widget.id ? [widget.id] : widgetSelectionIds(widget));
  mutate(() => {
    schema.widgets = schema.widgets.filter((item) => !ids.has(item.id));
    syncGroupMetadata();
    reindexZ();
  });
  selectedIds.value = selectedIds.value.filter((id) => !ids.has(id));
  if (ids.has(memberEditId.value)) memberEditId.value = "";
}
function deleteSelected() {
  if (!selectedIds.value.length) return;
  mutate(() => {
    const ids = new Set(selectedIds.value);
    schema.widgets = schema.widgets.filter((item) => !ids.has(item.id));
    syncGroupMetadata();
    reindexZ();
  });
  selectedIds.value = [];
  memberEditId.value = "";
}
function copyWidgets() {
  clipboardWidgets.value = selectedIds.value
    .map((id) =>
      widgetById(id) ? JSON.parse(JSON.stringify(widgetById(id))) : null,
    )
    .filter(Boolean);
  clipboardWidgets.value.forEach(widget => {
    if (widget.groupId && (memberEditId.value === widget.id || !widgetSelectionIds(widget).every(id => selectedIds.value.includes(id)))) delete widget.groupId;
  });
  clipboardGroups.value = normalizeDashboardGroups(clipboardWidgets.value, schema.groups);
  if (clipboardWidgets.value.length)
    ElMessage.success(`已复制 ${clipboardWidgets.value.length} 个组件`);
}
function cutWidgets() {
  copyWidgets();
  deleteSelected();
}
function pasteWidgets() {
  if (!clipboardWidgets.value.length) return;
  mutate(() => {
    const groups = new Map();
    clipboardWidgets.value.forEach((item) => {
      if (item.groupId && !groups.has(item.groupId))
        groups.set(item.groupId, newId("group"));
    });
    const pasted = translateDashboardClones(clipboardWidgets.value
      .map((item) => cloneWidget(item, 0, groups))
      .filter(Boolean), 32, { width: canvasWidth.value, height: canvasHeight.value });
    schema.widgets.push(...pasted);
    schema.groups = { ...schema.groups, ...copyDashboardGroupMetadata(clipboardGroups.value, groups) };
    syncGroupMetadata();
    reindexZ();
    memberEditId.value = "";
    selectedIds.value = pasted.map((item) => item.id);
  });
}

function setLayerOrder(list) {
  list.forEach((item, index) => {
    item.layout.z = index + 1;
  });
  schema.widgets = [...list];
}
function reindexZ() {
  setLayerOrder(
    [...schema.widgets].sort((a, b) => (a.layout.z || 0) - (b.layout.z || 0)),
  );
}
function reorderLayerItems(list, isSelected, direction) {
  const chosen = list.filter(isSelected), rest = list.filter(item => !isSelected(item));
  if (direction === "top") return [...rest, ...chosen];
  if (direction === "bottom") return [...chosen, ...rest];
  const result = [...list];
  if (direction === "up") {
    for (let index = result.length - 2; index >= 0; index -= 1) {
      if (isSelected(result[index]) && !isSelected(result[index + 1])) [result[index], result[index + 1]] = [result[index + 1], result[index]];
    }
  } else if (direction === "down") {
    for (let index = 1; index < result.length; index += 1) {
      if (isSelected(result[index]) && !isSelected(result[index - 1])) [result[index], result[index - 1]] = [result[index - 1], result[index]];
    }
  }
  return result;
}
function moveLayer(direction) {
  if (!selectedIds.value.length) return;
  const list = [...orderedWidgets.value];
  const selected = new Set(selectedIds.value);
  const member = widgetById(memberEditId.value);
  let reordered;
  if (member?.groupId && selected.size === 1 && selected.has(member.id)) {
    const members = reorderLayerItems(list.filter(widget => widget.groupId === member.groupId), widget => selected.has(widget.id), direction);
    let index = 0;
    reordered = list.map(widget => widget.groupId === member.groupId ? members[index++] : widget);
  } else {
    const units = new Map();
    list.forEach(widget => {
      const key = widget.groupId ? `group:${widget.groupId}` : `widget:${widget.id}`;
      const unit = units.get(key) || { widgets: [], z: 0 };
      unit.widgets.push(widget);
      unit.z = widget.layout.z;
      units.set(key, unit);
    });
    reordered = reorderLayerItems([...units.values()].sort((a, b) => a.z - b.z), unit => unit.widgets.some(widget => selected.has(widget.id)), direction).flatMap(unit => unit.widgets);
  }
  if (reordered.some((widget, index) => widget.id !== list[index].id)) mutate(() => setLayerOrder(reordered));
}
function canGroupSelection() {
  const widgets = expandedSelectionWidgets();
  return !widgets.some(widget => widget.state?.locked) && dashboardLayoutUnitCount(widgets) >= 2;
}
function groupSelectionVisualBounds(widgets) {
  // 选框覆盖旋转后的可见矩形；缩放计算仍使用原始布局快照。
  return getDashboardSelectionBounds(widgets.map(widget => {
    const layout = widget.layout;
    const radians = (Number(layout.rotate) || 0) * Math.PI / 180;
    const w = Math.abs(layout.w * Math.cos(radians)) + Math.abs(layout.h * Math.sin(radians));
    const h = Math.abs(layout.w * Math.sin(radians)) + Math.abs(layout.h * Math.cos(radians));
    return { ...widget, layout: { ...layout, x: layout.x + (layout.w - w) / 2, y: layout.y + (layout.h - h) / 2, w, h } };
  }));
}
function canUngroupSelection() {
  const widgets = expandedSelectionWidgets();
  return widgets.length > 0 && widgets.every(widget => widget.groupId && !widget.state?.locked);
}
function expandedSelectionWidgets() {
  const ids = new Set(selectedIds.value.map(widgetById).filter(Boolean).flatMap(widgetSelectionIds));
  return schema.widgets.filter(widget => ids.has(widget.id));
}
function groupSelected() {
  if (!canGroupSelection()) return;
  focusCanvas();
  memberEditId.value = "";
  const ids = new Set(selectedIds.value.map(widgetById).filter(Boolean).flatMap(widgetSelectionIds));
  mutate(() => {
    const id = newId("group");
    schema.widgets
      .filter((item) => ids.has(item.id))
      .forEach((item) => {
        item.groupId = id;
      });
    syncGroupMetadata();
  });
  selectedIds.value = [...ids];
}
function ungroupSelected() {
  if (!canUngroupSelection()) return;
  focusCanvas();
  const groups = new Set(
    selectedIds.value.map((id) => widgetById(id)?.groupId).filter(Boolean),
  );
  if (!groups.size) return;
  mutate(() => {
    schema.widgets
      .filter((item) => groups.has(item.groupId))
      .forEach((item) => {
        delete item.groupId;
      });
    syncGroupMetadata();
  });
  memberEditId.value = "";
}
function toggleVisible(widget, memberOnly = false) {
  const members = (memberOnly ? [widget.id] : widgetSelectionIds(widget)).map(widgetById).filter(Boolean);
  const visible = members.some(item => item.state?.visible === false);
  mutate(() => {
    members.forEach(item => { item.state = { ...(item.state || {}), visible }; });
  });
}
function toggleLocked(widget, memberOnly = false) {
  const members = (memberOnly ? [widget.id] : widgetSelectionIds(widget)).map(widgetById).filter(Boolean);
  const locked = !members.some(item => item.state?.locked === true);
  mutate(() => {
    members.forEach(item => { item.state = { ...(item.state || {}), locked }; });
  });
}

function changeZoom(delta) {
  canvasScale.value = Math.min(
    1.2,
    Math.max(0.2, Math.round((canvasScale.value + delta) * 100) / 100),
  );
}
function setZoom(value) {
  if (value === "fit") {
    fitCanvas();
    return;
  }
  const percent = Number(value);
  if (!Number.isFinite(percent)) return;
  canvasScale.value = Math.min(1.2, Math.max(0.2, percent / 100));
}
function fitCanvas() {
  const el = canvasViewport.value;
  if (!el) return;
  const availableWidth = el.clientWidth - 72;
  const availableHeight = el.clientHeight - 72;
  canvasScale.value = Math.min(
    1,
    Math.max(
      0.2,
      Math.floor(
        Math.min(
          availableWidth / canvasWidth.value,
          availableHeight / canvasHeight.value,
        ) * 100,
      ) / 100,
    ),
  );
}

function persistDraft() {
  if (historyPending.value) endHistory();
  const savedSnapshot = JSON.stringify(schema);
  saving.value = true;
  return saveDashboardDraft(pageId, { schemaJson: schemaJson() })
    .then(() => {
      // Changes made while this request is in flight still need another save.
      draftDirty.value = JSON.stringify(schema) !== savedSnapshot;
      ElMessage.success("草稿已保存");
    })
    .finally(() => {
      saving.value = false;
    });
}
function save() {
  return persistDraft();
}
function publish() {
  const action = () => {
    publishing.value = true;
    return publishDashboardPage(pageId, { publishNote: "设计器发布" })
      .then(() => {
        draftDirty.value = false;
        ElMessage.success("发布成功");
        load();
      })
      .finally(() => {
        publishing.value = false;
      });
  };
  return draftDirty.value ? persistDraft().then(action) : action();
}
function preview(mode = "current") {
  previewing.value = true;
  const targetUrl = router.resolve({
    path: `/dashboard/runtime/${pageId}`,
    query: { preview: "1", ...(mode === "fullscreen" ? { fullscreen: "1" } : {}) },
  }).href;
  const popup =
    mode === "new"
      ? window.open("about:blank", "_blank", "noopener,noreferrer")
      : null;
  const action = () => {
    if (popup && !popup.closed) {
      popup.location.href = targetUrl;
      popup.focus?.();
    } else
      router.push({
        path: `/dashboard/runtime/${pageId}`,
        query: { preview: "1", ...(mode === "fullscreen" ? { fullscreen: "1" } : {}) },
      });
  };
  (draftDirty.value ? persistDraft() : Promise.resolve())
    .then(action)
    .finally(() => {
      previewing.value = false;
    });
}
function previewCommand(command) {
  preview(command);
}
function leaveDesigner(action) {
  if (!draftDirty.value) {
    action();
    return;
  }
  ElMessageBox.confirm(
    "当前页面有未保存修改，离开后这些修改将丢失。",
    "离开设计器",
    {
      type: "warning",
      confirmButtonText: "放弃修改",
      cancelButtonText: "继续编辑",
    },
  )
    .then(() => action())
    .catch(() => {});
}
async function runtime() {
  if (checkingRuntime.value) return;
  if (!canRunPage.value) {
    ElMessage.warning("页面尚未发布或已停用，可使用预览检查草稿");
    return;
  }
  checkingRuntime.value = true;
  try {
    const res = await listPublishedDashboardPages({ keyword: page.pageCode });
    if (!isDashboardPageRunnable(page, res.data || [])) {
      ElMessage.warning("页面尚未发布或已停用，可使用预览检查草稿");
      return;
    }
    leaveDesigner(() => router.push(`/dashboard/runtime/${pageId}`));
  } catch {
    // 请求封装统一显示失败原因；保留当前设计内容，避免打开错误运行页。
  } finally {
    checkingRuntime.value = false;
  }
}
function sourceManagement() {
  leaveDesigner(() =>
    router.push({ path: "/dashboard/integration", query: { tab: "outbound" } }),
  );
}
function datasetManagement() {
  leaveDesigner(() =>
    router.push({ path: "/dashboard/integration", query: { tab: "datasets" } }),
  );
}
function back() {
  leaveDesigner(() => router.push("/dashboard/page"));
}
function handleMore(command) {
  if (command === "page-settings") {
    selectedIds.value = [];
    return;
  }
  if (command === "shortcuts") {
    shortcutVisible.value = true;
    return;
  }
  if (command === "export-json") {
    exportSchema();
    return;
  }
  if (command === "import-json") {
    importInput.value?.click();
    return;
  }
  if (command === "copy-page") {
    ElMessageBox.prompt("请输入新页面名称", "复制页面", {
      inputValue: `${page.pageName || "大屏"} 副本`,
      inputValidator: (value) => (value?.trim() ? true : "名称不能为空"),
    })
      .then(({ value }) =>
        copyDashboardPage(pageId, {
          pageCode: `${page.pageCode || "screen"}-copy-${Date.now()}`,
          pageName: value,
          remark: `复制自 ${page.pageName || page.pageCode}`,
        }),
      )
      .then((res) => {
        ElMessage.success("页面已复制");
        if (res?.data?.pageId)
          router.push(`/dashboard/designer/${res.data.pageId}`);
      })
      .catch(() => {});
  }
}

function exportSchema() {
  const blob = new Blob([schemaJson()], {
    type: "application/json;charset=utf-8",
  });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `${page.pageCode || "dashboard-page"}-${new Date().toISOString().slice(0, 10)}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
  ElMessage.success("页面 JSON 已导出");
}

function importSchema(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const parsed = JSON.parse(String(reader.result || ""));
      if (
        !parsed ||
        typeof parsed !== "object" ||
        Array.isArray(parsed) ||
        !Array.isArray(parsed.widgets)
      )
        throw new Error("schema");
      const normalized = normalizeSchema(parsed);
      mutate(() => {
        Object.assign(schema, normalized);
      });
      selectedIds.value = [];
      future.value = [];
      ElMessage.success("页面 JSON 已导入，请保存草稿后再发布");
    } catch {
      ElMessage.error("页面 JSON 无法解析或结构不受支持");
    }
  };
  reader.onerror = () => ElMessage.error("页面 JSON 读取失败");
  reader.readAsText(file);
}

watch(selectedIds, () => {
  if (memberEditId.value && (!selectedIds.value.includes(memberEditId.value) || !widgetById(memberEditId.value)?.groupId)) memberEditId.value = "";
  syncBatchStyle();
});
watch(selectedId, syncStaticRowsEditor);
watch(
  () =>
    schema.widgets
      .map(
        (widget) =>
          `${widget.id}|${widget.binding?.sourceType || ""}|${widget.binding?.datasetCode || ""}|${JSON.stringify(widget.binding?.parameters || {})}`,
      )
      .join(";"),
  () => {
    if (!loading.value) refreshAllWidgetPreviews();
  },
);
watch(
  schema,
  () => {
    scheduleDesignChartRender();
    // 部分 Element Plus 控件只提供 change 回调，无法在变更前调用 beginHistory。
    // 以最近一次已确认快照兜底，确保主题、开关和数字控件也能撤销/重做；
    // 正在进行的输入/拖动仍由 beginHistory/endHistory 合并成一次操作。
    if (loading.value || historyPending.value) return;
    const current = JSON.stringify(schema);
    if (current === lastSchemaSnapshot) return;
    history.value.push(lastSchemaSnapshot);
    if (history.value.length > 60) history.value.shift();
    future.value = [];
    lastSchemaSnapshot = current;
    draftDirty.value = true;
  },
  { deep: true },
);
watch(
  [() => componentPreview.visible, () => componentPreview.widget],
  ([visible]) => {
    if (visible) nextTick(renderComponentPreviewChart);
    else disposeComponentPreviewChart();
  },
);

function onKeydown(event) {
  const target = event.target;
  if (iconPickerVisible.value || target?.closest?.('.designer-icon-picker-dialog')) {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") event.preventDefault();
    return;
  }
  const editing =
    target?.tagName === "INPUT" ||
    target?.tagName === "TEXTAREA" ||
    target?.isContentEditable ||
    Boolean(target?.closest?.('select,button,[role="slider"],[role="spinbutton"],[role="combobox"],[role="switch"],[role="tab"]'));
  const key = event.key.toLowerCase();
  if (!editing && event.code === "Space") {
    event.preventDefault();
    spacePressed.value = true;
    return;
  }
  if ((event.metaKey || event.ctrlKey) && key === "s") {
    event.preventDefault();
    save();
    return;
  }
  if (editing) return;
  if ((event.metaKey || event.ctrlKey) && key === "g") {
    event.preventDefault();
    event.shiftKey ? ungroupSelected() : groupSelected();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && key === "a") {
    event.preventDefault();
    selectAllWidgets();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && key === "z") {
    event.preventDefault();
    event.shiftKey ? redo() : undo();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && key === "y") {
    event.preventDefault();
    redo();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && key === "c") {
    event.preventDefault();
    copyWidgets();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && key === "x") {
    event.preventDefault();
    cutWidgets();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && key === "v") {
    event.preventDefault();
    pasteWidgets();
    return;
  }
  if (event.key === "Delete" || event.key === "Backspace") {
    event.preventDefault();
    deleteSelected();
    return;
  }
  if (event.key === "Escape") {
    finishPointer(event);
    closeContextMenu();
    selectedIds.value = [];
    memberEditId.value = "";
    panMode.value = false;
    return;
  }
  if (
    ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(event.key) &&
    selectedIds.value.length
  ) {
    event.preventDefault();
    const step = event.shiftKey ? 10 : 1;
    const dx = event.key === "ArrowLeft" ? -step : event.key === "ArrowRight" ? step : 0;
    const dy = event.key === "ArrowUp" ? -step : event.key === "ArrowDown" ? step : 0;
    const changes = selectionTranslation(dx, dy);
    if (changes.length) mutate(() => applyLayoutChanges(changes));
  }
}

function onKeyup(event) {
  if (event.code === "Space") spacePressed.value = false;
}
function resetCanvasPanShortcut() {
  spacePressed.value = false;
  finishPointer({ type: "blur" });
}

onMounted(() => {
  previewClockTimer = window.setInterval(() => { previewNow.value = Date.now(); }, 1000);
  load();
  window.addEventListener("keydown", onKeydown);
  window.addEventListener("keyup", onKeyup);
  window.addEventListener("blur", resetCanvasPanShortcut);
  window.addEventListener("pointermove", handlePointerMove);
  window.addEventListener("pointerup", finishPointer);
  window.addEventListener("pointercancel", finishPointer);
  window.addEventListener("resize", fitCanvas);
});
onBeforeUnmount(() => {
  window.clearInterval(previewClockTimer);
  closeContextMenu();
  if (designRenderTimer) window.clearTimeout(designRenderTimer);
  disposeDesignCharts();
  disposeComponentPreviewChart();
  window.removeEventListener("keydown", onKeydown);
  window.removeEventListener("keyup", onKeyup);
  window.removeEventListener("blur", resetCanvasPanShortcut);
  window.removeEventListener("pointermove", handlePointerMove);
  window.removeEventListener("pointerup", finishPointer);
  window.removeEventListener("pointercancel", finishPointer);
  window.removeEventListener("resize", fitCanvas);
});
</script>

<style scoped>
:global(body) {
  background: var(--el-bg-color-page, #eef2f7);
}
.designer-shell {
  --surface: var(--el-bg-color, #ffffff);
  --surface-muted: var(--el-fill-color-light, #f7f9fc);
  --border: var(--el-border-color-light, #e6ebf2);
  --text: var(--el-text-color-primary, #18212f);
  --muted: var(--el-text-color-secondary, #7e8a9d);
  --accent: var(--el-color-primary, #409eff);
  height: calc(100vh - 84px);
  min-height: 640px;
  overflow: hidden;
  background: var(--el-bg-color-page, #eef2f7);
  color: var(--text);
  display: flex;
  flex-direction: column;
}
.designer-shell.is-dark {
  --accent: var(--el-color-primary, #409eff);
}
.page-name-editor {
  width: 210px;
}
.page-name-editor :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px #a8ded3 inset;
}
.edit-page-name {
  color: #8b98a8;
  padding: 4px;
}
.edit-page-name:hover {
  color: #127b6d;
  background: #e9f8f4;
}
.preview-actions {
  display: inline-flex;
  align-items: center;
}
.preview-actions > .el-button {
  border-radius: 5px 0 0 5px;
}
.preview-options {
  min-width: 28px;
  margin-left: -1px;
  padding: 8px 6px;
  border-radius: 0 5px 5px 0;
}
.designer-header {
  height: 58px;
  flex: 0 0 58px;
  padding: 0 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  z-index: 4;
}
.header-left,
.header-center,
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-left {
  min-width: 300px;
}
.header-center {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
}
.header-actions {
  min-width: 355px;
  justify-content: flex-end;
}
.back-button {
  color: #637083;
  font-size: 13px;
}
.header-divider {
  height: 22px;
  width: 1px;
  background: var(--border);
  margin: 0 8px;
}
.header-divider.small {
  height: 18px;
  margin: 0 4px;
}
.page-title-wrap {
  display: flex;
  flex-direction: column;
  line-height: 1.1;
}
.page-title {
  font-size: 14px;
  font-weight: 680;
  color: #1d2939;
}
.page-code {
  color: #9aa5b4;
  font-size: 10px;
  margin-top: 3px;
}
.dirty-tag,
.saved-tag {
  margin-left: 5px;
  font-size: 10px;
  border-radius: 5px;
}
.saved-tag {
  opacity: 0.72;
}
.more-button {
  padding: 8px 5px;
}
.data-entry-actions {
  display: flex;
  align-items: center;
  gap: 0;
}
.data-entry-actions .data-manage-button {
  padding: 7px 5px;
  color: #697789;
  font-size: 11px;
}
.data-entry-actions .data-manage-button:hover {
  color: #127b6d;
  background: #e9f8f4;
}
.designer-body {
  display: flex;
  flex: 1;
  min-height: 0;
}
.left-panel,
.inspector-panel {
  width: 268px;
  flex: 0 0 268px;
  background: var(--surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.inspector-panel {
  border-right: 0;
  border-left: 1px solid var(--border);
  width: 306px;
  flex-basis: 306px;
}
.panel-tabs {
  height: 46px;
  display: flex;
  border-bottom: 1px solid var(--border);
  padding: 0 12px;
  gap: 4px;
}
.panel-tabs button {
  flex: 1;
  border: 0;
  background: none;
  color: #7e8a9d;
  font-size: 12px;
  cursor: pointer;
  position: relative;
  display: inline-flex;
  gap: 5px;
  align-items: center;
  justify-content: center;
}
.panel-tabs button.active {
  color: #1d2939;
  font-weight: 650;
}
.panel-tabs button.active::after {
  content: "";
  position: absolute;
  height: 2px;
  left: 14px;
  right: 14px;
  bottom: 0;
  background: var(--accent);
  border-radius: 2px;
}
.components-panel,
.layers-panel {
  overflow: auto;
  flex: 1;
  padding: 16px 14px;
}
.panel-intro {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 16px;
}
.panel-intro > span {
  color: #a4afbd;
  font-size: 11px;
}
.panel-heading {
  font-size: 13px;
  font-weight: 700;
  color: #253144;
}
.component-group {
  margin-bottom: 20px;
}
.group-heading {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #7d899b;
  font-size: 11px;
  font-weight: 650;
  margin: 0 2px 8px;
}
.group-heading span {
  margin-left: auto;
  font-size: 10px;
  color: #b1bac6;
  font-weight: 500;
}
.component-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(64px, 1fr));
  gap: 6px;
}
.component-item {
  min-height: 70px;
  padding: 8px 4px 7px;
  border: 1px solid #edf0f4;
  border-radius: 7px;
  background: #fbfcfe;
  color: #596678;
  cursor: pointer;
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 6px;
  transition:
    border-color 0.16s,
    background 0.16s,
    transform 0.16s;
}
.component-item:hover {
  border-color: #a8ded3;
  background: #f1fbf8;
  transform: translateY(-1px);
}
.component-icon,
.layer-type-icon {
  width: 27px;
  height: 27px;
  border-radius: 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.component-label {
  font-size: 11px;
  white-space: nowrap;
}
.icon-teal {
  color: #0b9c89;
  background: #d9f7ef;
}
.icon-blue {
  color: #3078d9;
  background: #e1edff;
}
.icon-violet {
  color: #7657d9;
  background: #eee9ff;
}
.icon-amber {
  color: #b2730c;
  background: #fff1d2;
}
.icon-red {
  color: #d85b5b;
  background: #ffe6e6;
}
.icon-green {
  color: #2a9b5b;
  background: #e0f6e8;
}
.icon-pink {
  color: #cf5f9e;
  background: #ffe6f2;
}
.icon-orange {
  color: #d66d29;
  background: #ffeadc;
}
.icon-cyan {
  color: #198ba6;
  background: #def6fa;
}
.icon-purple {
  color: #8c50c4;
  background: #f2e5ff;
}
.icon-slate {
  color: #68768b;
  background: #edf1f5;
}
.palette-tip,
.data-quality-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  color: #8995a5;
  font-size: 11px;
  line-height: 1.55;
  padding: 10px;
  background: #f8fafc;
  border: 1px solid #edf0f4;
  border-radius: 7px;
}
.palette-tip .el-icon,
.data-quality-note .el-icon {
  color: #1da891;
  margin-top: 2px;
  flex: 0 0 auto;
}
.layers-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 13px;
}
.layer-count {
  color: #a0aab8;
  font-size: 10px;
}
.layers-empty {
  padding: 70px 0;
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 8px;
  color: #aeb8c4;
  font-size: 12px;
}
.layers-empty .el-icon {
  font-size: 28px;
  color: #c9d1db;
}
.layers-empty small {
  color: #c2cad4;
  font-size: 10px;
}
.workspace {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #e9eef4;
}
.workspace-toolbar {
  height: auto;
  flex: 0 0 auto;
  min-height: var(--dashboard-designer-toolbar-height, 44px);
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  align-items: center;
  justify-content: space-between;
  padding: 6px 16px;
  background: rgba(255, 255, 255, 0.9);
  border-bottom: 1px solid var(--border);
  color: #8390a1;
}
.tool-group,
.zoom-tools,
.canvas-info {
  display: flex;
  align-items: center;
  gap: 3px;
}
.tool-group { flex-wrap: wrap; }
.arrangement-trigger { display: inline-flex; }
.icon-picker-trigger { width: 100%; }
.icon-picker-trigger :deep(> span) { width: 100%; min-width: 0; gap: 10px; }
.icon-picker-trigger__preview { display: inline-flex; flex: 0 0 24px; align-items: center; justify-content: center; }
.icon-picker-trigger__label { flex: 1; min-width: 0; overflow: hidden; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.canvas-selection-box {
  position: absolute;
  z-index: 2147483647;
  pointer-events: none;
  border: 1px solid var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 14%, transparent);
}
.canvas-widget.selected {
  outline: 1px solid var(--el-color-primary);
  outline-offset: 1px;
}
.canvas-widget.primary {
  outline-width: 2px;
}
.tool-group .el-button,
.zoom-tools .el-button {
  color: #7f8b9b;
  padding: 7px;
}
.tool-group .el-button:hover,
.zoom-tools .el-button:hover,
.tool-group .toggled {
  color: #178c7a;
  background: #e9f8f4;
}
.toolbar-divider {
  height: 16px;
  width: 1px;
  background: #dfe5ec;
  margin: 0 8px;
}
.canvas-info {
  font-size: 11px;
  color: #98a3b1;
}
.zoom-value {
  border: 0;
  min-width: 52px;
  padding: 5px 4px;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  background: none;
  color: #667488;
  font-size: 11px;
  cursor: pointer;
}
.zoom-value:hover {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.zoom-value .el-icon {
  font-size: 10px;
}
.canvas-viewport {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 34px;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  background-color: #e9eef4;
  background-image: radial-gradient(#d6dde6 0.7px, transparent 0.7px);
  background-size: 16px 16px;
}
.canvas-holder {
  position: relative;
  flex: 0 0 auto;
}
.canvas-surface {
  position: relative;
  transform-origin: top left;
  overflow: hidden;
  box-shadow: 0 18px 42px rgba(33, 50, 71, 0.17);
  border-radius: 2px;
  background-repeat: no-repeat;
}
.canvas-surface.show-grid {
  background-image: linear-gradient(
      rgba(255, 255, 255, 0.055) 1px,
      transparent 1px
    ),
    linear-gradient(90deg, rgba(255, 255, 255, 0.055) 1px, transparent 1px);
  background-size: 32px 32px;
}
.canvas-watermark {
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.12;
  color: #d4deeb;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  letter-spacing: 4px;
  transform: rotate(-20deg);
}
.canvas-widget {
  position: absolute;
  box-sizing: border-box;
  overflow: visible;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #d9e3ef;
  transition:
    box-shadow 0.12s,
    border-color 0.12s;
  user-select: none;
}
.canvas-widget:not(.locked) {
  cursor: move;
}
.canvas-widget.selected {
  border-color: rgba(53, 212, 176, 0.8);
  box-shadow:
    0 0 0 1px rgba(53, 212, 176, 0.32),
    0 8px 16px rgba(0, 0, 0, 0.12);
}
.canvas-widget.primary {
  border-color: #35d4b0;
  box-shadow:
    0 0 0 1px #35d4b0,
    0 0 0 4px rgba(53, 212, 176, 0.13);
}
.canvas-widget.locked {
  cursor: default;
}
.widget-selection-label {
  position: absolute;
  z-index: 5;
  left: -1px;
  top: -23px;
  padding: 3px 7px;
  background: #35d4b0;
  color: #08271f;
  font-size: 10px;
  line-height: 16px;
  border-radius: 3px 3px 0 0;
  white-space: nowrap;
}
.widget-preview-content {
  position: absolute;
  inset: 0;
  padding: calc(var(--widget-title-height, 25px) + var(--widget-padding-top, 13px))
    var(--widget-padding-right, 14px) var(--widget-padding-bottom, 13px)
    var(--widget-padding-left, 14px);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  color: var(--accent);
}
.widget-preview-content.icon-preview-content {
  align-items: var(--widget-align-items, center);
}
.widget-preview-content.metric-preview-content {
  width: 100%;
  box-sizing: border-box;
  align-items: var(--widget-align-items, center);
  text-align: var(--widget-text-align, center);
}
.preview-kpi {
  max-width: 100%;
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  letter-spacing: 1px;
}
.preview-kpi + small {
  color: #9aa9bb;
  font-size: 12px;
  margin-top: 5px;
}
.preview-flip-cells {
  display: inline-flex;
  align-items: center;
  gap: var(--flip-cell-gap, 8px);
}
.preview-flip-cells b {
  width: var(--flip-cell-width, 42px);
  height: var(--flip-cell-height, 58px);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border: 1px solid var(--flip-cell-border, var(--accent));
  border-radius: 4px;
  background: var(--flip-cell-background, rgba(28, 56, 86, 0.72));
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  line-height: 1;
}
.preview-text {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-align-items, center);
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  text-align: var(--widget-text-align, center);
  white-space: pre-wrap;
}
.preview-image {
  font-size: 32px;
  color: #6f8097;
}
.preview-image + span {
  color: #8b99ab;
  font-size: 11px;
  margin-top: 7px;
}
.preview-lines {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.preview-lines i {
  display: block;
  height: 6px;
  border-radius: 6px;
  background: linear-gradient(
    90deg,
    rgba(53, 212, 176, 0.7) 0 58%,
    rgba(255, 255, 255, 0.1) 58%
  );
}
.preview-lines i:nth-child(2) {
  background: linear-gradient(
    90deg,
    rgba(82, 141, 233, 0.7) 0 75%,
    rgba(255, 255, 255, 0.1) 75%
  );
}
.preview-lines i:nth-child(3) {
  background: linear-gradient(
    90deg,
    rgba(195, 129, 60, 0.7) 0 40%,
    rgba(255, 255, 255, 0.1) 40%
  );
}
.preview-lines i:nth-child(4) {
  background: linear-gradient(
    90deg,
    rgba(167, 100, 222, 0.7) 0 66%,
    rgba(255, 255, 255, 0.1) 66%
  );
}
.preview-chart {
  align-items: flex-end;
  display: flex;
  height: 70%;
  width: 83%;
  gap: 7px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.15);
}
.preview-chart i {
  flex: 1;
  min-height: 10px;
  background: linear-gradient(
    180deg,
    rgba(53, 212, 176, 0.9),
    rgba(53, 212, 176, 0.15)
  );
  border-radius: 3px 3px 0 0;
}
.preview-line-chart i {
  background: linear-gradient(
    180deg,
    rgba(82, 141, 233, 0.9),
    rgba(82, 141, 233, 0.15)
  );
}
.preview-pie-chart,
.preview-ring-chart {
  width: 110px;
  height: 110px;
  border-radius: 50%;
  background: conic-gradient(
    #35d4b0 0 32%,
    #5489e8 32% 61%,
    #a875df 61% 83%,
    #e1a54d 83%
  );
}
.preview-ring-chart {
  position: relative;
}
.preview-ring-chart::after {
  content: "";
  position: absolute;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #152132;
  inset: 27px;
}
.preview-gauge,
.preview-progress {
  width: 130px;
  height: 65px;
  border-radius: 130px 130px 0 0;
  background: conic-gradient(
    from 270deg at 50% 100%,
    #e56d5b 0 25%,
    #e7ab47 25% 55%,
    #35d4b0 55% 76%,
    rgba(255, 255, 255, 0.12) 76%
  );
}
.preview-progress {
  height: 14px;
  border-radius: 14px;
  background: linear-gradient(
    90deg,
    #35d4b0 0 70%,
    rgba(255, 255, 255, 0.12) 70%
  );
}
.preview-decoration {
  width: 80%;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
}
.group-context-actions { display: flex; gap: 4px; }
.group-context-actions > button { flex: 1; justify-content: center; }
.canvas-group-selection {
  position: absolute;
  border: 1px dashed var(--el-color-primary);
  pointer-events: none;
  box-sizing: border-box;
}
.canvas-group-selection.locked { border-color: var(--el-color-warning); }
.canvas-group-selection .resize-handle { pointer-events: auto; border-color: var(--el-color-primary); }
.group-selection-label {
  position: absolute;
  bottom: calc(100% + 5px);
  left: -1px;
  padding: 3px 8px;
  border-radius: 3px;
  background: var(--el-color-primary);
  color: var(--el-color-white);
  font-size: 13px;
  white-space: nowrap;
  cursor: move;
  pointer-events: auto;
}
.locked .group-selection-label { background: var(--el-color-warning); cursor: default; }
.resize-handle {
  position: absolute;
  width: 8px;
  height: 8px;
  background: #fff;
  border: 1px solid #2ab89c;
  border-radius: 2px;
  z-index: 6;
}
.handle-nw {
  left: -5px;
  top: -5px;
  cursor: nwse-resize;
}
.handle-n {
  left: calc(50% - 4px);
  top: -5px;
  cursor: ns-resize;
}
.handle-ne {
  right: -5px;
  top: -5px;
  cursor: nesw-resize;
}
.handle-e {
  right: -5px;
  top: calc(50% - 4px);
  cursor: ew-resize;
}
.handle-se {
  right: -5px;
  bottom: -5px;
  cursor: nwse-resize;
}
.handle-s {
  left: calc(50% - 4px);
  bottom: -5px;
  cursor: ns-resize;
}
.handle-sw {
  left: -5px;
  bottom: -5px;
  cursor: nesw-resize;
}
.handle-w {
  left: -5px;
  top: calc(50% - 4px);
  cursor: ew-resize;
}
.canvas-empty {
  position: absolute;
  inset: 40% 0 auto;
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 8px;
  color: #8795a7;
  font-size: 13px;
  pointer-events: none;
}
.canvas-empty .el-icon {
  font-size: 28px;
  color: #5fa695;
}
.canvas-empty small {
  color: #9aa8b8;
  font-size: 11px;
}
.workspace-footer {
  height: 30px;
  flex: 0 0 30px;
  padding: 0 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #8c98a8;
  font-size: 10px;
  background: rgba(255, 255, 255, 0.86);
  border-top: 1px solid var(--border);
}
.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #36b37e;
  margin-right: 6px;
}
.status-dot.dirty {
  background: #e3a53c;
}
.footer-hint {
  color: #aab3bf;
}
.inspector-head {
  min-height: 68px;
  padding: 14px 14px 11px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border);
}
.inspector-head h2 {
  margin: 4px 0 0;
  font-size: 14px;
  color: #253144;
  font-weight: 700;
  max-width: 190px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.inspector-head > div:first-child {
  flex: 1;
  min-width: 0;
  margin-right: 6px;
}
.inspector-eyebrow {
  color: #9ba6b4;
  text-transform: uppercase;
  letter-spacing: 0.07em;
  font-size: 9px;
}
.inspector-head-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.page-inspector-head {
  min-height: 72px;
}
.page-setting-icon {
  color: #8b98a8;
  font-size: 18px;
}
.inspector-tabs {
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}
.inspector-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 12px;
}
.inspector-tabs :deep(.el-tabs__nav-wrap::after) {
  background-color: #eef1f5;
}
.inspector-tabs :deep(.el-tabs__item) {
  height: 38px;
  padding: 0 10px;
  font-size: 11px;
  color: #8894a3;
}
.inspector-tabs :deep(.el-tabs__item.is-active) {
  color: #147f70;
}
.inspector-tabs :deep(.el-tabs__active-bar) {
  background-color: #26ad96;
}
.inspector-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.inspector-tabs :deep(.el-tab-pane) {
  height: 100%;
}
.inspector-scroll {
  height: 100%;
  overflow: auto;
  padding: 15px 16px 28px;
}
.property-section {
  padding-bottom: 18px;
  margin-bottom: 17px;
  border-bottom: 1px solid #edf0f4;
}
.property-section:last-child {
  border-bottom: 0;
}
.section-label {
  color: #6f7d90;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.03em;
  margin-bottom: 12px;
}
.section-label-inline {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.inspector-scroll :deep(.el-form-item) {
  margin-bottom: 13px;
}
.inspector-scroll :deep(.el-form-item__label) {
  color: #8a96a5;
  font-size: 10px;
  line-height: 20px;
  padding: 0;
}
.inspector-scroll :deep(.el-input__wrapper),
.inspector-scroll :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px #e8edf2 inset;
  border-radius: 5px;
  min-height: var(--dashboard-inspector-control-height, 32px);
}
.inspector-scroll :deep(.el-input-number) {
  width: 100%;
}
.position-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 8px;
}
.position-grid label,
.range-field,
.refresh-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  color: #8a96a5;
  font-size: 10px;
}
.position-grid :deep(.el-input-number .el-input__wrapper) {
  padding-left: 8px;
  padding-right: 22px;
}
.range-field {
  margin-top: 14px;
}
.range-field > span {
  align-self: flex-end;
  margin-top: -19px;
  color: #687689;
}
.range-field :deep(.el-slider) {
  margin: 0 4px;
}
.toggle-section {
  padding-bottom: 6px;
}
.toggle-row {
  min-height: 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #687689;
  font-size: 11px;
  margin-bottom: 8px;
}
.toggle-row span {
  display: flex;
  align-items: center;
  gap: 7px;
}
.toggle-row .el-icon {
  color: #98a5b4;
}
.color-row {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  margin-bottom: 10px;
  color: #8995a5;
  font-size: 10px;
}
.dataset-summary {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 10px;
  border-radius: 5px;
  background: #f6faf9;
  color: #536477;
  font-size: 11px;
}
.dataset-summary small {
  color: #9aa7b5;
  margin-left: auto;
  font-size: 9px;
}
.dataset-status {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #d0d7df;
}
.status-active {
  background: #2bb58f;
}
.status-draft {
  background: #e5a53e;
}
.status-disabled {
  background: #d86b6b;
}
.mapping-row,
.param-row {
  display: grid;
  grid-template-columns: 78px 1fr;
  gap: 7px;
  align-items: center;
  margin-bottom: 8px;
}
.mapping-row label,
.param-row label {
  color: #8995a5;
  font-size: 10px;
}
.param-row label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.param-row label small {
  color: #b0bac5;
  font-size: 9px;
}
.field-hint {
  margin: 9px 0 0;
  color: #a4afbb;
  font-size: 10px;
  line-height: 1.5;
}
.filter-row {
  display: grid;
  grid-template-columns: 1fr 68px 1fr 24px;
  gap: 4px;
  margin-bottom: 6px;
}
.filter-row .el-button {
  padding: 4px 0;
}
.form-field-editor {
  display: grid;
  grid-template-columns: 1fr 1fr 110px 1fr 1.6fr 24px;
  gap: 5px;
  margin-bottom: 7px;
  align-items: center;
}
.form-field-editor :deep(.el-input__wrapper),
.form-field-editor :deep(.el-select__wrapper) {
  min-height: var(--dashboard-inspector-control-height, 32px);
}
.advanced-form-fields {
  margin-top: 8px;
  border-top: 1px solid #eef1f4;
  border-bottom: 0;
}
.advanced-form-fields :deep(.el-collapse-item__header) {
  color: #7f8c9b;
  font-size: 11px;
}
.advanced-form-fields :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
}
.data-quality-note {
  margin-top: 2px;
  font-size: 10px;
}
.refresh-field {
  flex-direction: row;
  align-items: center;
  gap: 7px;
}
.refresh-field .el-input-number {
  flex: 1;
}
.shortcut-list {
  display: flex;
  flex-direction: column;
  gap: 13px;
}
.shortcut-list > div {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #617083;
  font-size: 13px;
}
.shortcut-list span {
  margin-left: 7px;
}
.shortcut-list kbd {
  border: 1px solid #dbe2ea;
  border-bottom-width: 2px;
  background: #f8fafc;
  border-radius: 4px;
  padding: 3px 7px;
  color: #57677a;
  font-size: 11px;
}
.designer-context-menu {
  position: fixed;
  z-index: 20;
  min-width: 168px;
  padding: 6px;
  border: 1px solid #dce5ed;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 12px 32px rgba(31, 49, 70, 0.2);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.designer-context-menu button {
  border: 0;
  border-radius: 5px;
  padding: 7px 9px;
  background: transparent;
  color: #566477;
  font-size: 11px;
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 7px;
}
.designer-context-menu button:hover:not(:disabled) {
  color: #127b6d;
  background: #e9f8f4;
}
.designer-context-menu button:disabled {
  color: #b4bdc8;
  cursor: not-allowed;
}
.designer-context-menu .context-separator {
  height: 1px;
  margin: 4px 2px;
  background: #edf0f4;
}
.designer-header {
  gap: 12px;
}
.header-left {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
}
.header-center {
  position: static;
  left: auto;
  flex: 0 0 auto;
  transform: none;
}
.header-actions {
  flex: 0 0 auto;
  min-width: 0;
  white-space: nowrap;
}
.page-title-wrap {
  min-width: 0;
  max-width: 220px;
}
.page-title,
.page-code {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.layer-row.dragging {
  opacity: 0.42;
  background: #eef7f5;
}
.canvas-viewport {
  display: block;
  justify-content: normal;
}
.canvas-holder {
  margin: 0 auto;
}
.canvas-viewport.pan-ready,
.canvas-viewport.pan-ready .canvas-widget,
.canvas-viewport.pan-ready .resize-handle {
  cursor: grab;
}
.canvas-viewport.pan-ready {
  touch-action: none;
}
.canvas-viewport.panning,
.canvas-viewport.panning * {
  cursor: grabbing !important;
  user-select: none;
}
.inspector-panel {
  width: 336px;
  flex-basis: 336px;
}
.inspector-panel .inspector-head h2 {
  max-width: 220px;
  font-size: 15px;
}
.inspector-panel .inspector-eyebrow {
  font-size: 10px;
}
.inspector-panel .inspector-tabs :deep(.el-tabs__item) {
  font-size: 12px;
}
.inspector-panel .inspector-scroll {
  padding: 16px 18px 30px;
}
.inspector-panel .section-label {
  margin-bottom: 14px;
  overflow: hidden;
  color: #5f6d80;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspector-panel .section-label-inline {
  gap: 10px;
}
.inspector-panel .section-label-inline > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspector-panel .field-hint,
.inspector-panel .data-quality-note {
  font-size: 11px;
  line-height: 1.6;
}
.inspector-panel .inspector-scroll :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 14px;
}
.inspector-panel .inspector-scroll :deep(.el-form-item__label) {
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  color: #778597;
  font-size: 11px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspector-panel .inspector-scroll :deep(.el-input),
.inspector-panel .inspector-scroll :deep(.el-select),
.inspector-panel .inspector-scroll :deep(.el-input-number),
.inspector-panel .inspector-scroll :deep(.el-date-editor) {
  width: 100%;
  min-width: 0;
}
.inspector-panel .inspector-scroll :deep(.el-input__inner),
.inspector-panel .inspector-scroll :deep(.el-select__selected-item),
.inspector-panel .inspector-scroll :deep(.el-textarea__inner) {
  font-size: 12px;
}
.inspector-panel .inspector-scroll :deep(.el-input__wrapper),
.inspector-panel .inspector-scroll :deep(.el-select__wrapper) {
  min-height: var(--dashboard-inspector-control-height, 32px);
}
.inspector-panel .inspector-scroll :deep(.el-input-number .el-input__wrapper) {
  padding-left: 10px;
  padding-right: 32px;
}
.inspector-panel .position-grid,
.inspector-panel .style-grid,
.inspector-panel .typography-grid,
.inspector-panel .title-image-grid,
.inspector-panel .title-padding-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.inspector-panel .typography-grid,
.inspector-panel .title-image-grid,
.inspector-panel .title-padding-grid {
  display: grid;
  margin-top: 14px;
}
.inspector-panel .style-grid,
.inspector-panel .legend-margin-grid {
  display: grid;
  margin-top: 14px;
}
.inspector-panel .typography-grid :deep(.el-form-item),
.inspector-panel .title-image-grid :deep(.el-form-item),
.inspector-panel .title-padding-grid :deep(.el-form-item),
.inspector-panel .style-grid :deep(.el-form-item),
.inspector-panel .legend-margin-grid :deep(.el-form-item) {
  display: block;
  min-width: 0;
  margin: 0;
}
.inspector-panel .typography-grid :deep(.el-form-item__label),
.inspector-panel .title-image-grid :deep(.el-form-item__label),
.inspector-panel .title-padding-grid :deep(.el-form-item__label),
.inspector-panel .style-grid :deep(.el-form-item__label),
.inspector-panel .legend-margin-grid :deep(.el-form-item__label) {
  display: block;
  width: 100% !important;
  height: 22px;
  margin: 0;
  padding: 0;
  overflow: hidden;
  color: #778597;
  font-size: 11px;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspector-panel .typography-grid :deep(.el-form-item__content),
.inspector-panel .title-image-grid :deep(.el-form-item__content),
.inspector-panel .title-padding-grid :deep(.el-form-item__content),
.inspector-panel .style-grid :deep(.el-form-item__content),
.inspector-panel .legend-margin-grid :deep(.el-form-item__content) {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  min-height: 32px;
  line-height: 32px;
}
.inspector-panel .style-grid :deep(.el-input),
.inspector-panel .style-grid :deep(.el-select),
.inspector-panel .style-grid :deep(.el-input-number),
.inspector-panel .legend-margin-grid :deep(.el-input-number),
.inspector-panel .typography-grid :deep(.el-input-number),
.inspector-panel .typography-grid :deep(.el-select),
.inspector-panel .title-image-grid :deep(.el-input-number),
.inspector-panel .title-image-grid :deep(.el-select),
.inspector-panel .title-padding-grid :deep(.el-input-number) {
  width: 100%;
  min-width: 0;
}
.inspector-panel .inspector-scroll :deep(.el-input-number .el-input__inner) {
  text-align: left;
  font-size: 12px;
}
.inspector-panel .inspector-scroll :deep(.el-input-number__increase),
.inspector-panel .inspector-scroll :deep(.el-input-number__decrease) {
  width: 28px;
}
.inspector-panel .style-grid :deep(.el-switch),
.inspector-panel .legend-margin-grid :deep(.el-switch) {
  flex: 0 0 auto;
}
.inspector-panel .style-grid :deep(.el-slider) {
  width: calc(100% - 12px);
  margin: 0 6px;
}
.inspector-panel .position-grid > *,
.inspector-panel .style-grid > *,
.inspector-panel .typography-grid > *,
.inspector-panel .title-image-grid > *,
.inspector-panel .title-padding-grid > *,
.inspector-panel .color-row > *,
.inspector-panel .mapping-row > *,
.inspector-panel .param-row > * {
  min-width: 0;
}
.inspector-panel .position-grid label,
.inspector-panel .range-field,
.inspector-panel .refresh-field,
.inspector-panel .mapping-row label,
.inspector-panel .param-row label {
  color: #778597;
  font-size: 11px;
  line-height: 18px;
}
.inspector-panel .range-field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px 10px;
  margin-top: 16px;
}
.inspector-panel .range-field > span {
  grid-column: 2;
  align-self: auto;
  margin: 0;
  color: #58677a;
  white-space: nowrap;
}
.inspector-panel .range-field :deep(.el-slider) {
  grid-column: 1 / -1;
  width: calc(100% - 12px);
  margin: 0 6px;
}
.inspector-panel .toggle-row {
  gap: 10px;
  color: #657488;
  font-size: 12px;
}
.inspector-panel .toggle-row > span:first-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspector-panel .toggle-row > .el-switch,
.inspector-panel .toggle-row > .el-button {
  flex: 0 0 auto;
}
.inspector-panel .color-row {
  gap: 8px;
  color: #778597;
  font-size: 11px;
}
.inspector-panel .color-row label {
  white-space: nowrap;
}
.inspector-panel .background-color-row {
  margin-bottom: 0;
}
.inspector-panel :deep(.color-property-field) {
  grid-column: 1 / -1;
}
.inspector-panel .background-resource-row {
  align-items: stretch;
}
.inspector-panel .background-resource-row .el-select {
  flex: 1;
  min-width: 0;
}
.inspector-panel .background-resource-row :deep(.el-upload),
.inspector-panel .background-resource-row :deep(.el-button) {
  height: 32px;
}
.inspector-panel .resource-picker-section :deep(.el-select),
.inspector-panel .map-resource-section :deep(.el-select) {
  width: 100%;
}
.inspector-panel .mapping-row,
.inspector-panel .param-row {
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 9px;
}
.inspector-panel .mapping-row label,
.inspector-panel .param-row label,
.inspector-panel .page-filter-target-row > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspector-panel .filter-row {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
  padding: 9px;
  border: 1px solid #edf0f4;
  border-radius: 6px;
  background: #fbfcfe;
}
.inspector-panel .filter-row > :nth-child(3) {
  grid-column: 1 / -1;
}
.inspector-panel .filter-row > .el-button {
  grid-column: 2;
  justify-self: end;
}
.inspector-panel .form-field-editor {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
  padding: 10px;
  border: 1px solid #edf0f4;
  border-radius: 6px;
  background: #fbfcfe;
}
.inspector-panel .form-field-editor > :nth-child(5) {
  grid-column: 1 / -1;
}
.inspector-panel .form-field-editor > .el-button {
  grid-column: 2;
  justify-self: end;
}
.inspector-panel .page-filter-row {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
  padding: 9px;
  border: 1px solid #edf0f4;
  border-radius: 6px;
  background: #fbfcfe;
}
.inspector-panel .page-filter-row > .el-button {
  grid-column: 2;
  justify-self: end;
}
.inspector-panel .page-filter-target-row {
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 9px;
  font-size: 11px;
}
.inspector-panel .refresh-field {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}
.inspector-panel .refresh-field > span {
  white-space: nowrap;
}
.inspector-panel .legend-margin-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.inspector-panel .palette-option > span:last-child {
  font-size: 11px;
}
@media (max-width: 1280px) {
  .left-panel {
    width: 230px;
    flex-basis: 230px;
  }
  .inspector-panel {
    width: 300px;
    flex-basis: 300px;
  }
  .inspector-panel .inspector-scroll {
    padding-inline: 15px;
  }
  .header-center {
    display: none;
  }
  .header-actions {
    min-width: 0;
  }
  .canvas-info {
    display: none;
  }
}
@media (max-width: 900px) {
  .left-panel {
    display: none;
  }
  .inspector-panel {
    width: 290px;
    flex-basis: 290px;
  }
  .workspace-footer .footer-hint {
    display: none;
  }
}
@media (max-width: 560px) {
  .form-field-editor,
  .page-filter-row {
    grid-template-columns: 1fr 1fr;
  }
  .form-field-editor .el-button,
  .page-filter-row .el-button {
    justify-self: end;
  }
}
.tab-editor-item {
  padding: 9px 10px 10px;
  margin-bottom: 8px;
  border: 1px solid #edf0f4;
  border-radius: 6px;
  background: #fbfcfe;
  display: grid;
  gap: 7px;
}
.tab-editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #7b8796;
  font-size: 10px;
  font-weight: 650;
}
.tab-editor-item :deep(.el-input__wrapper),
.tab-editor-item :deep(.el-textarea__inner),
.tab-editor-item :deep(.el-select__wrapper) {
  min-height: var(--dashboard-inspector-control-height, 32px);
}
.advanced-tab-fields {
  margin-top: 8px;
  border-top: 1px solid #eef1f4;
  border-bottom: 0;
}
.advanced-tab-fields :deep(.el-collapse-item__header) {
  color: #7f8c9b;
  font-size: 11px;
}
.advanced-tab-fields :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
}
</style>

<style scoped>
.custom-html-design-frame {
  position: relative;
  width: 100%;
  height: 100%;
  border: 0;
  pointer-events: none;
  background: transparent;
}
.preview-dialog-custom-html {
  width: 100%;
  min-height: 260px;
  height: 260px;
  overflow: hidden;
  border: 1px solid #2c3d52;
  border-radius: 4px;
  background: #07111c;
}
.preview-dialog-custom-html iframe {
  width: 100%;
  height: 100%;
  border: 0;
  background: transparent;
}
.designer-chart-preview {
  position: relative;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}
.designer-video-preview {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.designer-image-preview {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  pointer-events: none;
}
.resource-warning {
  color: #d58a22;
}
.legend-margin-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  margin: 8px 0 10px;
}
.legend-margin-grid :deep(.el-form-item) {
  margin-bottom: 0;
}
.legend-margin-grid :deep(.el-input-number) {
  width: 100%;
}
.widget-preview-heading {
  position: absolute;
  z-index: 2;
  left: 10px;
  right: 10px;
  top: 0;
  height: var(--widget-title-height, 25px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  line-height: 1.25;
  pointer-events: none;
}
.widget-preview-heading span {
  font-weight: inherit;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}
.widget-preview-heading small {
  margin-left: 0;
  color: #aebccc;
  font-size: 0.82em;
  font-weight: 400;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}
.canvas-widget.title-image .widget-preview-heading {
  left: 0;
  right: 0;
  box-sizing: border-box;
}
.canvas-widget .widget-preview-content {
  padding-top: calc(
    var(--widget-title-height, 25px) + var(--widget-padding-top, 13px)
  );
}
.canvas-widget.title-hidden .widget-preview-content {
  padding-top: var(--widget-padding-top, 13px);
}
.canvas-widget.border-transparent:not(.selected) {
  border-color: transparent !important;
}
.canvas-widget.border-transparent.selected {
  border-color: rgba(53, 212, 176, 0.8) !important;
}
.canvas-widget.border-transparent.primary {
  border-color: #35d4b0 !important;
}
.canvas-widget.widget-embedded:not(.selected) {
  border-color: transparent;
  box-shadow: none;
}
.canvas-widget.widget-embedded .preview-button,
.canvas-widget.widget-embedded .preview-form span,
.canvas-widget.widget-embedded .preview-form b {
  background: transparent;
  box-shadow: none;
}
.component-preview-stage.border-transparent {
  border-color: transparent;
}
.component-preview-stage.widget-embedded {
  border-color: transparent;
  box-shadow: none;
}
.component-preview-stage.widget-embedded .preview-button,
.component-preview-stage.widget-embedded .preview-form span,
.component-preview-stage.widget-embedded .preview-form b {
  background: transparent;
  box-shadow: none;
}
.page-filter-row {
  display: grid;
  grid-template-columns: 1fr 1.1fr 0.85fr 1fr 24px;
  gap: 4px;
  margin-bottom: 7px;
}
.page-filter-target-row {
  display: grid;
  grid-template-columns: 74px 1fr;
  gap: 7px;
  align-items: center;
  margin: 0 0 9px;
  color: #8b97a6;
  font-size: 10px;
}
.page-filter-target-row .el-select {
  min-width: 0;
}
.refresh-inherit {
  display: flex;
  align-items: center;
  gap: 7px;
}
.refresh-inherit .el-input-number {
  flex: 1;
}
.refresh-inherit > span {
  color: #8794a3;
  font-size: 10px;
  white-space: nowrap;
}
.static-data-editor {
  margin-top: 10px;
}
.static-data-editor :deep(.el-textarea__inner) {
  min-height: 150px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  line-height: 1.55;
  color: #526276;
  background: #f8fafc;
}
.component-preview-dialog {
  padding: 3px 0 12px;
}
.component-preview-stage {
  position: relative;
  height: 360px;
  min-height: 290px;
  padding: var(--widget-padding-top, 13px) var(--widget-padding-right, 14px) var(--widget-padding-bottom, 13px) var(--widget-padding-left, 14px);
  overflow: hidden;
  border: 1px solid #2c3d52;
  display: flex;
  align-items: var(--widget-align-items, center);
  justify-content: var(--widget-content-align-y, center);
  flex-direction: column;
  gap: 20px;
  color: #dce8f5;
  box-shadow: 0 14px 30px rgba(15, 29, 45, 0.16);
}
.preview-dialog-title {
  flex: 0 0 var(--widget-title-height, 25px);
  align-self: stretch;
  min-height: var(--widget-title-height, 25px);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  justify-content: center;
  color: #9cabbc;
  font-size: 13px;
}
.preview-dialog-kpi {
  width: 100%;
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  text-align: var(--widget-text-align, center);
}
.preview-dialog-kpi small {
  margin-left: 8px;
  color: #95a5b6;
  font-size: 13px;
  font-weight: 400;
}
.preview-dialog-text {
  width: 100%;
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  white-space: pre-wrap;
  text-align: var(--widget-text-align, center);
}
.preview-dialog-icon {
  display: flex;
  line-height: 1;
  color: var(--accent);
  font-size: 64px;
}
.component-preview-chart {
  width: 100%;
  min-height: 250px;
}
.watermark-color {
  margin-top: 14px;
}
.style-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 8px;
  margin-top: 14px;
}
.style-grid :deep(.el-form-item) {
  margin-bottom: 10px;
}
.style-grid :deep(.el-form-item__label) {
  line-height: 18px;
}
.typography-grid,
.title-image-grid,
.title-padding-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.typography-grid :deep(.el-form-item),
.title-image-grid :deep(.el-form-item),
.title-padding-grid :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 0;
}
.typography-grid :deep(.el-form-item),
.title-padding-grid :deep(.el-form-item) {
  display: block;
}
.typography-grid :deep(.el-form-item__label),
.title-padding-grid :deep(.el-form-item__label) {
  display: block;
  width: auto !important;
  height: 20px;
  margin: 0;
  padding: 0;
  line-height: 20px;
  white-space: nowrap;
}
.typography-grid :deep(.el-form-item__content),
.title-padding-grid :deep(.el-form-item__content) {
  display: block;
  width: 100%;
  min-width: 0;
  line-height: normal;
}
.typography-grid :deep(.el-input-number),
.typography-grid :deep(.el-select),
.title-image-grid :deep(.el-input-number),
.title-image-grid :deep(.el-select),
.title-padding-grid :deep(.el-input-number) {
  width: 100%;
}
.single-number-form {
  width: 100%;
}
.single-number-form :deep(.el-form-item) {
  margin: 14px 0 0;
}
.single-number-form :deep(.el-input-number) {
  width: 100%;
}
.hidden-file-input {
  display: none;
}
.background-resource-row {
  display: flex;
  align-items: center;
  gap: 7px;
}
.background-resource-row .el-input {
  flex: 1;
}
.background-resource-row :deep(.el-upload) {
  display: inline-flex;
}
.palette-picker {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}
.palette-option {
  min-width: 0;
  padding: 7px 8px;
  border: 1px solid #e5ebf2;
  border-radius: 6px;
  background: #fbfcfe;
  color: #647286;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 7px;
  text-align: left;
}
.palette-option:hover,
.palette-option.active {
  border-color: #8fd5c7;
  background: #effaf7;
  color: #127b6d;
}
.palette-option.active {
  box-shadow: 0 0 0 1px rgba(53, 212, 176, 0.18);
}
.palette-option > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10px;
}
.palette-swatches {
  display: flex;
  flex: 0 0 auto;
}
.palette-swatches i {
  width: 9px;
  height: 22px;
  display: block;
}
.palette-swatches i:first-child {
  border-radius: 3px 0 0 3px;
}
.palette-swatches i:last-child {
  border-radius: 0 3px 3px 0;
}
.preview-statistics {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: space-between;
  gap: 12px;
  text-align: var(--widget-text-align, left);
}
.preview-statistics-main {
  min-width: 0;
}
.preview-statistics.stats-layout-horizontal .preview-statistics-main {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.preview-statistics.stats-layout-vertical .preview-statistics-main {
  display: flex;
  flex-direction: column;
  align-items: var(--widget-align-items, flex-start);
  justify-content: center;
}
.preview-statistics-label {
  display: block;
  margin-bottom: 6px;
  overflow: hidden;
  color: var(--stats-label-color, #9aabbd);
  font-size: var(--stats-label-size, 11px);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-statistics-main strong {
  display: flex;
  align-items: baseline;
  gap: 4px;
  color: var(--accent);
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  line-height: 1.1;
}
.preview-statistics-unit {
  color: var(--stats-unit-color, #9aabbd);
  font-size: var(--stats-unit-size, 12px);
  font-weight: 500;
}
.preview-statistics em {
  color: #8fa0b3;
  font-size: 10px;
  font-style: normal;
  white-space: nowrap;
}
.preview-statistics em.compare-up {
  color: var(--stats-up, #35d4b0);
}
.preview-statistics em.compare-down {
  color: var(--stats-down, #ef8d8d);
}
.preview-statistics.stats-label-after .preview-statistics-label {
  order: 2;
  margin-top: 4px;
  margin-bottom: 0;
}
.preview-statistics.stats-label-after .preview-statistics-main strong {
  order: 1;
}
.preview-access-list {
  width: 100%;
  height: 100%;
  overflow: hidden;
  text-align: left;
}
.preview-access-row {
  position: relative;
  height: var(--access-row-height, 72px);
  display: flex;
  align-items: center;
  box-sizing: border-box;
  gap: 10px;
  padding: 5px 2px;
  border-bottom: 1px solid rgba(45, 114, 170, 0.25);
  background: linear-gradient(90deg, rgba(7, 43, 76, 0.24), transparent);
}
.preview-access-row > img {
  width: var(--access-avatar-size, 56px);
  height: var(--access-avatar-size, 56px);
  flex: 0 0 var(--access-avatar-size, 56px);
  border: 2px solid #edfdff;
  border-radius: 50%;
  object-fit: cover;
  box-sizing: border-box;
}
.preview-access-list.access-vehicle .preview-access-row > img {
  padding: 3px;
  object-fit: contain;
  background: rgba(12, 45, 72, 0.94);
  box-shadow: inset 0 0 12px rgba(86, 199, 255, 0.18);
}
.preview-access-list.access-person .preview-access-row > img {
  object-fit: cover;
}
.preview-access-copy {
  min-width: 0;
  flex: 1;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-right: 48px;
}
.preview-access-primary {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 10px;
  white-space: nowrap;
}
.preview-access-primary b {
  flex: 0 0 auto;
  color: #f3f8fb;
  font-size: 16px;
}
.preview-access-primary span,
.preview-access-primary time,
.preview-access-copy > small {
  max-width: 100%;
  color: #cbd8e6;
  font-size: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-access-primary span {
  min-width: 0;
}
.preview-access-primary time {
  margin-left: auto;
}
.preview-access-copy > small {
  margin-top: 5px;
}
.preview-access-row em {
  position: absolute;
  right: 2px;
  bottom: 7px;
  min-width: 42px;
  height: 20px;
  border-radius: 4px;
  color: #fff;
  font-size: 10px;
  font-style: normal;
  line-height: 20px;
  text-align: center;
}
.preview-access-row em.success {
  background: #00d887;
}
.preview-access-row em.neutral {
  background: #b8c0c7;
  color: #59636b;
}
.preview-access-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8293a6;
  font-size: 12px;
}
.preview-dialog-access {
  min-height: 210px;
}
.preview-button {
  align-self: var(--widget-align-items, center);
  border: var(--widget-border-width, 1px)
    var(--widget-border-style, solid)
    var(--widget-border-color, rgba(53, 212, 176, 0.65));
  border-radius: 5px;
  padding: var(--button-padding-y, 7px) var(--button-padding-x, 18px);
  color: var(--accent);
  background: rgba(53, 212, 176, 0.12);
  font-size: var(--widget-font-size, 12px);
  font-weight: var(--widget-font-weight, 400);
}
.preview-tabs {
  width: 88%;
  display: flex;
  gap: 3px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
}
.preview-tabs span {
  padding: 5px 8px;
  color: #8697aa;
  font-size: 10px;
  border-bottom: 2px solid transparent;
}
.preview-tabs span.active {
  color: #35d4b0;
  border-color: #35d4b0;
}
.preview-tab-content {
  color: #b2c2d2;
  font-size: 11px;
  margin-top: 10px;
}
.preview-form {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  justify-content: center;
}
.preview-form span {
  padding: 5px 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: #8b9bad;
  border-radius: 4px;
  font-size: 10px;
}
.preview-form b {
  padding: 5px 10px;
  color: #35d4b0;
  border: 1px solid rgba(53, 212, 176, 0.55);
  border-radius: 4px;
  font-size: 10px;
  font-weight: 500;
}
.preview-clock-content {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: var(--widget-content-align-y, center);
  justify-content: var(--widget-align-items, center);
  gap: 10px;
  color: var(--accent);
  text-align: var(--widget-text-align, center);
}
.preview-clock-content .el-icon {
  font-size: 1.4em;
}
.preview-clock-content strong {
  font-size: var(--widget-font-size, 16px);
  font-weight: var(--widget-font-weight, 400);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.05em;
  white-space: nowrap;
}
.preview-dialog-clock {
  min-height: 160px;
}
.preview-weather,
.preview-dialog-weather {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--accent);
  font-size: 25px;
}
.preview-weather svg {
  width: 22px;
}
.preview-weather + small,
.preview-dialog-weather small {
  display: block;
  color: #95a5b6;
  font-size: 10px;
  margin-top: 7px;
}
.preview-color-block,
.preview-dialog-color-block {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #8b9bad;
  font-size: 10px;
}
.preview-color-block {
  flex-direction: column;
}
.preview-color-block::before,
.preview-dialog-color-block span {
  content: "";
  display: block;
  width: 62px;
  height: 62px;
  border-radius: 10px;
  background: #35d4b0;
  box-shadow: 0 0 18px rgba(53, 212, 176, 0.36);
}
.preview-map,
.preview-dialog-map {
  position: relative;
  width: 88%;
  height: 72%;
  min-height: 100px;
  border: 1px solid rgba(112, 171, 192, 0.3);
  border-radius: 50%;
  background: radial-gradient(
    ellipse,
    rgba(50, 128, 158, 0.3),
    rgba(12, 33, 54, 0.82) 68%
  );
}
.preview-map i,
.preview-dialog-map i {
  position: absolute;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #35d4b0;
  box-shadow:
    0 0 0 4px rgba(53, 212, 176, 0.2),
    0 0 14px #35d4b0;
}
.preview-dialog-media {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #8b9bad;
  font-size: 12px;
}
.preview-milestone-timeline {
  position: relative;
  width: 92%;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
  padding-top: 10px;
}
.preview-milestone-timeline::before {
  content: "";
  position: absolute;
  left: 8%;
  right: 8%;
  top: 16px;
  height: 2px;
  background: rgba(77, 142, 210, 0.45);
}
.preview-milestone-timeline > div {
  position: relative;
  z-index: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  color: #8092a8;
}
.preview-milestone-timeline i {
  width: 13px;
  height: 13px;
  border: 2px solid #477aa5;
  background: #13283b;
  transform: rotate(45deg);
  box-shadow: 0 0 0 3px rgba(35, 111, 177, 0.16);
}
.preview-milestone-timeline .done i,
.preview-milestone-timeline .active i {
  border-color: var(--timeline-done, #39d5de);
}
.preview-milestone-timeline .active i {
  border-color: var(--timeline-active, #35d4b0);
  background: var(--timeline-active, #35d4b0);
  box-shadow: 0 0 12px var(--timeline-active, #35d4b0);
}
.preview-milestone-timeline b {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #c5d5e6;
  font-size: 10px;
  font-weight: 500;
}
.preview-milestone-timeline span {
  font-size: 9px;
}
.preview-milestone-timeline small {
  color: #718397;
  font-size: 9px;
  white-space: nowrap;
}
.preview-milestone-timeline.labels-above b {
  order: 1;
  margin-bottom: 4px;
}
.preview-milestone-timeline.labels-above i {
  order: 2;
  margin-bottom: 6px;
}
.preview-milestone-timeline.labels-above span {
  order: 3;
}
.preview-milestone-timeline.labels-above small {
  order: 4;
}
.preview-milestone-timeline.labels-above.layout-spread {
  height: 100%;
  padding-top: 0;
}
.preview-milestone-timeline.labels-above.layout-spread::before {
  top: 47%;
}
.preview-milestone-timeline.labels-above.layout-spread > div {
  position: relative;
  gap: 0;
}
.preview-milestone-timeline.labels-above.layout-spread b,
.preview-milestone-timeline.labels-above.layout-spread i,
.preview-milestone-timeline.labels-above.layout-spread span,
.preview-milestone-timeline.labels-above.layout-spread small {
  position: absolute;
  margin: 0;
}
.preview-milestone-timeline.labels-above.layout-spread b {
  top: 23%;
}
.preview-milestone-timeline.labels-above.layout-spread i {
  top: calc(47% - 6px);
}
.preview-milestone-timeline.labels-above.layout-spread span {
  top: 66%;
}
.preview-milestone-timeline.labels-above.layout-spread small {
  top: 80%;
}
.designer-table-preview {
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  color: var(--table-text-color, #cbd8e6);
  font-size: var(--widget-font-size, 11px);
}
.designer-table-preview table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}
.designer-table-preview th,
.designer-table-preview td {
  height: var(--preview-row-height, 30px);
  box-sizing: border-box;
  padding: 0 var(--table-cell-padding, 6px);
  overflow: hidden;
  border-bottom: 1px solid rgba(135, 161, 184, 0.15);
  text-align: var(--widget-text-align, left);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.designer-table-preview th {
  color: var(--table-header-color, #8497aa);
  background: var(--table-header-background, transparent);
  font-weight: 500;
}
.designer-table-preview td:last-child {
  color: var(--table-value-color, var(--table-text-color, #cbd8e6));
}
.designer-table-preview .table-primary-column {
  width: var(--table-first-column-width, 50%);
}
.designer-table-preview.stripe tbody tr:nth-child(even) {
  background: rgba(93, 128, 153, 0.1);
}
.preview-dialog-table {
  width: 100%;
  max-height: 250px;
}
.designer-data-preview-state {
  position: absolute;
  z-index: 4;
  inset: var(--widget-title-height, 25px) 0 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
  background: rgba(9, 20, 33, 0.72);
  color: #91a4b8;
  font-size: 11px;
  text-align: center;
  pointer-events: none;
}
.designer-data-preview-state.dialog-state {
  inset: var(--widget-title-height, 25px) 0 0;
}
.preview-word-cloud {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  align-content: center;
  flex-wrap: wrap;
  gap: 5px 10px;
  overflow: hidden;
}
.preview-word-cloud span {
  line-height: 1.15;
  white-space: nowrap;
}
</style>

<style scoped>
.preview-statistics.stats-compact { gap: 6px; }
.preview-statistics.stats-compact .preview-statistics-label { margin-bottom: 3px; }
.preview-weather, .preview-dialog-weather, .preview-dialog-icon { font-size: var(--widget-font-size, 16px); font-weight: var(--widget-font-weight, 400); }
.widget-preview-content { justify-content: var(--widget-content-align-y, center); }
.preview-weather, .preview-dialog-weather { color: var(--accent); }
.preview-dialog-color-block { font-size: var(--widget-font-size, 16px); font-weight: var(--widget-font-weight, 400); text-align: var(--widget-text-align, center); }
</style>

<style scoped>
.preview-dialog-image { width: 100%; height: 100%; min-height: 0; flex: 1; overflow: hidden; display: flex; align-items: center; justify-content: center; }
.preview-dialog-empty { color: #9cabbc; font-size: 12px; text-align: center; }
.preview-color-block::before { background: inherit; }
</style>
