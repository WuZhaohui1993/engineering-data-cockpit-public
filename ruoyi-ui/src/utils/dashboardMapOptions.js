import * as echarts from 'echarts'

const optionalMapNumber = (value) => value === null || value === undefined || value === '' ? NaN : Number(value)
const safeMapNumber = (value, min, max, fallback) => {
  const number = optionalMapNumber(value)
  return Number.isFinite(number) ? Math.max(min, Math.min(max, number)) : fallback
}

export function buildDashboardMapOption(widget, mapName, { points = [], regions = [], flows = [] }, palette = ['#35d4b0', '#5b8ff9']) {
  const regionValues = regions
    .map((item) => Number(item.value))
    .filter(Number.isFinite);
  const configuredVisualMin = optionalMapNumber(widget.style?.mapVisualMin);
  const configuredVisualMax = optionalMapNumber(widget.style?.mapVisualMax);
  const visualMin = Number.isFinite(configuredVisualMin)
    ? configuredVisualMin
    : regionValues.length
      ? Math.min(...regionValues)
      : 0;
  const visualMaxCandidate = Number.isFinite(configuredVisualMax)
    ? configuredVisualMax
    : regionValues.length
      ? Math.max(...regionValues)
      : 100;
  const visualMax =
    visualMaxCandidate > visualMin ? visualMaxCandidate : visualMin + 1;
  const areaColor =
    widget.style?.mapAreaGradient === true
      ? new echarts.graphic.RadialGradient(0.5, 0.5, 0.82, [
          { offset: 0, color: widget.style?.mapCenterColor || "#235c6c" },
          { offset: 1, color: widget.style?.mapEdgeColor || "#10283d" },
        ])
      : widget.style?.mapAreaColor || "#17344a";
  const geo = {
    map: mapName,
    roam: widget.style?.mapRoam === true,
    silent: false,
    zoom: safeMapNumber(widget.style?.mapZoom, 0.5, 20, 1),
    aspectScale: safeMapNumber(widget.style?.mapAspectScale, 0.5, 2, 1),
    layoutCenter: [
      `${safeMapNumber(widget.style?.mapLayoutX, 0, 100, 50)}%`,
      `${safeMapNumber(widget.style?.mapLayoutY, 0, 100, 50)}%`,
    ],
    layoutSize: `${safeMapNumber(widget.style?.mapLayoutSize, 10, 200, 96)}%`,
    label: {
      show: widget.style?.mapShowLabels === true,
      color: widget.style?.mapLabelColor || "#dbeaf4",
      fontSize: safeMapNumber(widget.style?.mapLabelFontSize, 8, 32, 10),
    },
    itemStyle: {
      areaColor,
      borderColor: widget.style?.mapBorderColor || "#4c8297",
      borderWidth: safeMapNumber(widget.style?.mapBorderWidth, 0, 10, 0.8),
      shadowBlur: safeMapNumber(widget.style?.mapShadowBlur, 0, 100, 0),
      shadowOffsetX: safeMapNumber(
        widget.style?.mapShadowOffsetX,
        -100,
        100,
        0,
      ),
      shadowOffsetY: safeMapNumber(
        widget.style?.mapShadowOffsetY,
        -100,
        100,
        0,
      ),
      shadowColor: widget.style?.mapShadowColor || "rgba(0,0,0,.35)",
    },
    emphasis: {
      itemStyle: { areaColor: widget.style?.mapEmphasisColor || "#235c6c" },
    },
  };
  const scatterData = points
    .filter((point) => point.coordinate)
    .map((point, index) => ({
      name: point.label,
      label: point.label,
      value: [...point.coordinate, Number(point.value) || 0],
      row: point.row,
      itemStyle: { color: palette[index % palette.length] },
      symbolSize: safeMapNumber(widget.style?.mapPointSize, 4, 32, 9),
    }));
  const baseOption = {
    tooltip: {
      trigger: "item",
      renderMode: "richText",
      formatter: (params) =>
        `${params?.data?.label || params?.name || ""}: ${Array.isArray(params?.data?.value) ? (params.data.value[2] ?? "-") : (params?.data?.value ?? "-")}`,
    },
    geo,
    visualMap:
      widget.style?.mapVisualMap === true && regionValues.length
        ? {
            show: true,
            min: visualMin,
            max: visualMax,
            left: 8,
            bottom: 8,
            itemWidth: 10,
            itemHeight: 72,
            calculable: false,
            textStyle: {
              color: widget.style?.mapLabelColor || "#dbeaf4",
              fontSize: 9,
            },
            inRange: {
              color: [
                widget.style?.mapVisualMinColor || "#17344a",
                widget.style?.mapVisualMaxColor || "#35d4b0",
              ],
            },
            seriesIndex: 0,
          }
        : undefined,
    series: [
      {
        type: "map",
        geoIndex: 0,
        name: widget.style?.title == null ? "" : String(widget.style.title).trim(),
        data: regions,
        selectedMode: false,
        emphasis: { label: { show: widget.style?.mapShowLabels === true } },
      },
      {
        type: "scatter",
        coordinateSystem: "geo",
        geoIndex: 0,
        data: scatterData,
      },
    ],
  };
  if (widget.type === "map-heat") {
    const heatValues = scatterData.map((item) => item.value[2]);
    const heatMin = Number.isFinite(configuredVisualMin) ? configuredVisualMin : Math.min(...heatValues, 0);
    const heatMax = Number.isFinite(configuredVisualMax) ? configuredVisualMax : Math.max(...heatValues, 1);
    baseOption.visualMap = {
      show: widget.style?.mapVisualMap === true,
      min: heatMin,
      max: heatMax > heatMin ? heatMax : heatMin + 1,
      dimension: 2,
      seriesIndex: 1,
      left: 8, bottom: 8, itemWidth: 10, itemHeight: 72,
      textStyle: { color: widget.style?.mapLabelColor || "#dbeaf4" },
      inRange: { color: [widget.style?.mapVisualMinColor || "#17344a", widget.style?.mapVisualMaxColor || "#35d4b0"] },
    };
    baseOption.series = [
      { type: "map", geoIndex: 0, data: regions, selectedMode: false },
      {
        type: "heatmap",
        coordinateSystem: "geo",
        geoIndex: 0,
        pointSize: safeMapNumber(widget.style?.mapPointSize, 4, 32, 12),
        blurSize: 24,
        data: scatterData,
      },
    ];
  } else if (widget.type === "map-bar" || widget.type === "map-ranking") {
    const ranked = [...scatterData].sort(
      (a, b) => Number(b.value?.[2] || 0) - Number(a.value?.[2] || 0),
    );
    const pointSize = safeMapNumber(widget.style?.mapPointSize, 4, 32, 9);
    const maximum = Math.max(...ranked.map((item) => Math.abs(item.value[2])), 1);
    baseOption.series = [
      { type: "map", geoIndex: 0, data: regions, selectedMode: false },
      {
        type: "scatter",
        coordinateSystem: "geo",
        geoIndex: 0,
        data: ranked.map((item, index) => ({
          ...item,
          symbol: "rect",
          symbolSize: [pointSize, Math.max(4, Math.sqrt(Math.abs(item.value[2]) / maximum) * pointSize * 5)],
          symbolOffset: [0, -Math.max(4, Math.sqrt(Math.abs(item.value[2]) / maximum) * pointSize * 5) / 2],
          label: {
            show: widget.style?.mapShowLabels === true,
            position: "top",
            formatter:
              widget.type === "map-ranking"
                ? `${index + 1}. ${item.name}`
                : item.name,
            color: widget.style?.mapLabelColor || "#dbeaf4",
            fontSize: safeMapNumber(widget.style?.mapLabelFontSize, 8, 32, 10),
          },
        })),
      },
    ];
  } else if (widget.type === "map-flow" || widget.type === "map-timeline") {
    const groups =
      widget.type === "map-timeline"
        ? [...new Set(flows.map((item) => item.group || "默认"))]
        : ["默认"];
    const makeSeries = (group) => {
      const groupFlows = flows.filter(
        (item) =>
          widget.type !== "map-timeline" || (item.group || "默认") === group,
      );
      return [
        {
          type: "lines",
          coordinateSystem: "geo",
          geoIndex: 0,
          effect: {
            show: true,
            period: 5,
            trailLength: 0.2,
            symbol: "arrow",
            symbolSize: 5,
          },
          lineStyle: { color: palette[0], width: 1.2, curveness: 0.18 },
          data: groupFlows.map((item) => ({
            name: `${item.fromName} → ${item.toName}`,
            coords: [item.from, item.to],
            value: item.value,
            row: item.row,
          })),
        },
        {
          type: "effectScatter",
          coordinateSystem: "geo",
          geoIndex: 0,
          rippleEffect: { brushType: "stroke" },
          symbolSize: safeMapNumber(widget.style?.mapPointSize, 4, 32, 9),
          itemStyle: { color: palette[1] || palette[0] },
          data: groupFlows.flatMap((item) => [
            {
              name: item.fromName,
              value: [...item.from, item.value],
              row: item.row,
            },
            {
              name: item.toName,
              value: [...item.to, item.value],
              row: item.row,
            },
          ]),
        },
      ];
    };
    if (widget.type === "map-timeline" && groups.length > 1) {
      baseOption.timeline = {
        axisType: "category",
        autoPlay: true,
        playInterval: Math.max(
          1500,
          Number(widget.style?.carouselInterval || 4) * 1000,
        ),
        data: groups,
        left: 10,
        right: 10,
        bottom: 4,
        label: { color: "#a9b8c8", fontSize: 9 },
      };
      baseOption.options = groups.map((group) => ({
        series: [
          { type: "map", geoIndex: 0, data: regions, selectedMode: false },
          ...makeSeries(group),
        ],
      }));
      baseOption.series = [
        { type: "map", geoIndex: 0, data: regions, selectedMode: false },
        ...makeSeries(groups[0]),
      ];
    } else
      baseOption.series = [
        { type: "map", geoIndex: 0, data: regions, selectedMode: false },
        ...makeSeries(groups[0]),
      ];
  }
  return baseOption;
}

export function dashboardMapData(widget, rows, formatValue = (_field, value) => value == null ? '' : String(value)) {
  const map = widget.binding?.fieldMap || {}
  const labelKey = map.label || map.name || map.category || Object.keys(rows[0] || {})[0]
  const valueKey = map.value || Object.keys(rows[0] || {})[1]
  const longitudeKey = map.longitude || map.lng || map.lon || map.x || 'longitude'
  const latitudeKey = map.latitude || map.lat || map.y || 'latitude'
  const coordinate = (longitude, latitude) => {
    const x = optionalMapNumber(longitude), y = optionalMapNumber(latitude)
    return Number.isFinite(x) && Number.isFinite(y) && x >= -180 && x <= 180 && y >= -90 && y <= 90 ? [x, y] : null
  }
  const regions = rows.slice(0, 500).map((row) => ({
    name: formatValue(labelKey, row[labelKey]), value: optionalMapNumber(row[valueKey]), row,
  })).filter((item) => item.name)
  const points = rows.slice(0, 200).map((row) => ({
    label: formatValue(labelKey, row[labelKey]), value: row[valueKey], row,
    coordinate: coordinate(row[longitudeKey], row[latitudeKey]),
  }))
  const flows = rows.slice(0, 500).map((row) => ({
    row,
    from: coordinate(row[map.fromLongitude || 'fromLongitude'], row[map.fromLatitude || 'fromLatitude']),
    to: coordinate(row[map.toLongitude || 'toLongitude'], row[map.toLatitude || 'toLatitude']),
    fromName: formatValue(map.fromName || 'fromName', row[map.fromName || 'fromName']) || '起点',
    toName: formatValue(map.toName || 'toName', row[map.toName || 'toName']) || '终点',
    value: optionalMapNumber(row[valueKey]) || 0,
    group: formatValue(map.group || 'group', row[map.group || 'group']) || '默认',
  })).filter((item) => item.from && item.to)
  return { points, regions, flows }
}
