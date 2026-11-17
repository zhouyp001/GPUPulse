/* 服务器监控面板：原生 WebSocket + Canvas 实时渲染，无框架、无构建。 */
(() => {
  'use strict';

  /* ---------- 全局状态 ---------- */
  const servers = new Map();    // serverId -> 服务器视图状态
  const allGauges = [];         // 所有仪表引用，rAF 统一平滑插值

  const container = document.getElementById('servers');
  const tableView = document.getElementById('table-view');
  const emptyEl = document.getElementById('empty');
  const connEl = document.getElementById('conn');
  const viewFullBtn = document.getElementById('view-full');
  const viewCompactBtn = document.getElementById('view-compact');
  const viewTableBtn = document.getElementById('view-table');
  const viewBarBtn = document.getElementById('view-bar');
  const clearBtn = document.getElementById('clear-offline');

  /* 清除离线/未连接记录：调服务端清空接口，服务端会广播 remove 消息让面板移除对应卡片 */
  clearBtn.onclick = async () => {
    try {
      const r = await fetch('/api/servers/clear-offline', { method: 'POST' });
      const d = await r.json();
      setConn('已清除 ' + (d.removed || 0) + ' 条离线记录', 'ok');
    } catch (e) {
      setConn('清除失败', 'err');
    }
  };

  /* ---------- 视图模式：full=标准（环形仪表），compact=精简（高密度无图标），table=汇总宽表，bar=条形图 ---------- */
  const VIEWS = ['full', 'compact', 'table', 'bar'];
  let viewMode = VIEWS.includes(localStorage.getItem('viewMode')) ? localStorage.getItem('viewMode') : 'full';

  function setView(mode) {
    viewMode = mode;
    localStorage.setItem('viewMode', mode);
    viewFullBtn.classList.toggle('active', mode === 'full');
    viewCompactBtn.classList.toggle('active', mode === 'compact');
    viewTableBtn.classList.toggle('active', mode === 'table');
    viewBarBtn.classList.toggle('active', mode === 'bar');
    document.body.classList.toggle('compact', mode === 'compact');
    const inTable = mode === 'table';
    container.hidden = inTable;        // 表格视图隐藏卡片区，显示汇总宽表
    tableView.hidden = !inTable;
    barControls.hidden = mode !== 'bar';   // 仅条形视图显示指标勾选栏
    if (inTable) renderTable();
    else servers.forEach((s) => { if (s.frame) renderFrame(s, s.frame); });
  }
  viewFullBtn.onclick = () => setView('full');
  viewCompactBtn.onclick = () => setView('compact');
  viewTableBtn.onclick = () => setView('table');
  viewBarBtn.onclick = () => setView('bar');

  /* ---------- 条形图：显示哪些指标（勾选控制，全局生效并持久化） ---------- */
  const barControls = document.getElementById('bar-controls');
  const allMetricKeys = ['util', 'temp', 'mem', 'power'];
  let barMetrics;
  try {
    barMetrics = new Set(JSON.parse(localStorage.getItem('barMetrics') || 'null') || allMetricKeys);
  } catch (e) { barMetrics = new Set(allMetricKeys); }
  barMetrics = new Set(allMetricKeys.filter((k) => barMetrics.has(k)));
  barControls.querySelectorAll('input').forEach((cb) => {
    cb.checked = barMetrics.has(cb.dataset.metric);
    cb.onchange = () => {
      if (cb.checked) barMetrics.add(cb.dataset.metric); else barMetrics.delete(cb.dataset.metric);
      localStorage.setItem('barMetrics', JSON.stringify([...barMetrics]));
      servers.forEach((s) => { s.bar = null; if (s.frame) renderFrame(s, s.frame); }); // 重建全部条形图
    };
  });

  const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';

  const COLORS = {
    cyan: '#58a6ff',
    green: '#3fb950',
    gold: '#e3b341',
    orange: '#d29922',
    red: '#f85149',
    track: 'rgba(128,146,164,0.18)',
  };

  /* 分组条形图里按 GPU index 分配的颜色（同一卡跨指标组颜色一致），数量不足时循环 */
  const GPU_COLORS = ['#58a6ff', '#3fb950', '#e3b341', '#f85149', '#bc8cff', '#f778ba', '#39c5cf', '#d29922'];

  /* ---------- 连接管理（断线指数退避重连） ---------- */
  let ws = null, retry = 0, retryTimer = null;

  function connect() {
    ws = new WebSocket(wsUrl);
    ws.onopen = () => { setConn('已连接', 'ok'); retry = 0; };
    ws.onmessage = (ev) => { try { handle(JSON.parse(ev.data)); } catch (e) { console.warn('帧解析失败', e); } };
    ws.onclose = () => { setConn('连接断开，重连中…', 'err'); scheduleReconnect(); };
    ws.onerror = () => { try { ws.close(); } catch (e) {} };
  }

  function scheduleReconnect() {
    if (retryTimer) return;
    const delay = Math.min(1000 * Math.pow(2, retry++), 10000);
    retryTimer = setTimeout(() => { retryTimer = null; connect(); }, delay);
  }

  function setConn(text, cls) { connEl.textContent = text; connEl.className = 'chip ' + cls; }

  /* ---------- 协议消息处理 ---------- */
  function handle(msg) {
    switch (msg.type) {
      case 'snapshot':
        Object.values(msg.servers || {}).forEach(upsert);
        break;
      case 'update':
        upsert(msg.data);
        break;
      case 'sync': {
        const s = servers.get(msg.data.server);
        if (s) { s.online = msg.data.online; s.lastSeen = msg.data.lastSeen; }
        break;
      }
      case 'remove':
        (msg.servers || [msg.server]).forEach(removeServer);
        break;
    }
    emptyEl.hidden = servers.size !== 0;
    refreshTableIfActive();
  }

  /* 表格视图激活时，数据更新后重建汇总宽表 */
  function refreshTableIfActive() {
    if (viewMode === 'table' && !tableView.hidden) renderTable();
  }

  function removeServer(id) {
    const s = servers.get(id);
    if (!s) return;
    for (const g of s.gauges.values()) {
      const i = allGauges.indexOf(g);
      if (i >= 0) allGauges.splice(i, 1);
    }
    s.gauges.clear();
    s.refs.el.remove();
    servers.delete(id);
  }

  function upsert(node) {
    let s = servers.get(node.server);
    if (!s) {
      s = { online: node.online, lastSeen: node.lastSeen, frame: node.frame, gauges: new Map(), refs: makeCard(node.server) };
      servers.set(node.server, s);
    } else {
      s.online = node.online;
      s.lastSeen = node.lastSeen;
    }
    if (node.frame) renderFrame(s, node.frame);
  }

  /* ---------- DOM 构建 ---------- */
  function makeCard(id) {
    const card = document.createElement('section');
    card.className = 'card';
    card.id = 'card-' + id.replace(/[^\w.-]/g, '_');

    const head = document.createElement('div');
    head.className = 'card-head';
    const dot = document.createElement('span');
    dot.className = 'dot';
    const h2 = document.createElement('h2');
    h2.textContent = id;
    const count = document.createElement('span');
    count.className = 'gpu-count';
    const seen = document.createElement('span');
    seen.className = 'last-seen';
    seen.textContent = '';
    head.append(dot, h2, count, seen);

    const gpus = document.createElement('div');
    gpus.className = 'gpus';
    const customs = document.createElement('div');
    customs.className = 'customs';

    card.append(head, gpus, customs);
    container.appendChild(card);
    return { el: card, dot, count, seen, gpus, customs };
  }

  /* ---------- 帧渲染 ---------- */
  function renderFrame(s, frame) {
    // 移除上一帧遗留的仪表引用，避免 rAF 循环重复绘制已分离的 canvas（切换视图时同样适用）
    for (const g of s.gauges.values()) {
      const i = allGauges.indexOf(g);
      if (i >= 0) allGauges.splice(i, 1);
    }
    s.gauges.clear();

    if (viewMode === 'compact') renderCompact(s, frame);
    else if (viewMode === 'bar') renderBar(s, frame);
    else renderFull(s, frame);
  }

  const fmtGb = (mb) => (mb / 1024).toFixed(1) + 'G';

  /* ---------- 标准视图：GPU 环形仪表 + 自定义指标卡片 ---------- */
  function renderFull(s, frame) {
    const refs = s.refs;
    refs.el.classList.toggle('off', !s.online);

    // ---- GPU 卡片区（每张 GPU 一个子面板）----
    refs.gpus.innerHTML = '';
    const gpuCount = (frame.gpus && frame.gpus.length) || 0;
    refs.count.textContent = gpuCount ? gpuCount + ' GPU' : '';
    if (gpuCount) {
      frame.gpus.forEach((g, gi) => {
        const block = document.createElement('div');
        block.className = 'gpu';

        const head = document.createElement('div');
        head.className = 'gpu-head';
        const badge = document.createElement('span');
        badge.className = 'gpu-badge';
        badge.textContent = 'GPU #' + g.index;
        const name = document.createElement('span');
        name.className = 'gpu-name';
        name.textContent = g.name || '';
        name.title = g.name || '';
        head.append(badge, name);

        const row = document.createElement('div');
        row.className = 'gpu-row';

        const specs = [
          { key: 'util', name: '利用率', value: g.utilization || 0, max: 100, unit: '%', fixed: 0, color: 'cyan' },
          { key: 'temp', name: '温度',   value: g.temperature || 0, max: 100, unit: '°C', fixed: 0, color: tempColor(g.temperature || 0) },
          { key: 'mem',  name: '显存',   value: g.memoryUsed || 0,  max: g.memoryTotal || 1, unit: 'GB', fixed: 1, color: 'green' },
          { key: 'pow',  name: '功耗',   value: g.powerDraw || 0,   max: 400, unit: 'W', fixed: 1, color: 'gold' },
        ];
        specs.forEach((sp) => {
          const gauge = makeGauge(sp);
          gauge.target = sp.value;
          s.gauges.set(gi + '.' + sp.key, gauge);
          allGauges.push(gauge);
          row.appendChild(gauge.cell);
        });

        block.append(head, row);
        refs.gpus.appendChild(block);
      });
    } else {
      const none = document.createElement('div');
      none.className = 'gpu-title';
      none.textContent = '无 GPU 数据';
      refs.gpus.appendChild(none);
    }

    // ---- 自定义指标区 ----
    refs.customs.innerHTML = '';
    if (frame.metrics && Object.keys(frame.metrics).length) {
      Object.entries(frame.metrics).forEach(([k, v]) => {
        const tile = document.createElement('div');
        tile.className = 'metric';
        const kd = document.createElement('div');
        kd.className = 'k';
        kd.textContent = k;
        const vd = document.createElement('div');
        vd.className = 'v';
        vd.textContent = Number.isFinite(v) ? v.toFixed(1) : '--';
        tile.append(kd, vd);
        refs.customs.appendChild(tile);
      });
    }
  }

  /* ---------- 精简视图：纯文本表格，高信息密度，无图标 ---------- */
  /* 按 GPU 数（数据条数）排序卡片：同条数的卡片聚到同行，行高一致更紧凑 */
  function sortCompactCards() {
    if (viewMode !== 'compact') return;
    [...container.children]
      .sort((a, b) => (b._gpuCount || 0) - (a._gpuCount || 0))   // GPU 多的排前
      .forEach((el) => container.appendChild(el));               // 依次移到末尾完成重排
  }

  function renderCompact(s, frame) {
    const refs = s.refs;
    refs.el.classList.toggle('off', !s.online);

    // ---- GPU：紧凑表格（每卡一行）----
    refs.gpus.innerHTML = '';
    const gpus = (frame.gpus && frame.gpus.length) ? frame.gpus : [];
    refs.el._gpuCount = gpus.length;                 // 供精简视图按条数分组排序
    refs.count.textContent = gpus.length ? gpus.length + ' GPU' : '';
    if (gpus.length) {
      const table = document.createElement('table');
      table.className = 'ctable';
      const thead = document.createElement('thead');
      const thr = document.createElement('tr');
      ['#', '型号', '利用率', '温度', '显存', '功耗'].forEach((t) => {
        const th = document.createElement('th');
        th.textContent = t;
        thr.appendChild(th);
      });
      thead.appendChild(thr);
      const tbody = document.createElement('tbody');
      gpus.forEach((g) => {
        const tr = document.createElement('tr');
        const cells = [
          { v: g.index, cls: 'num' },
          { v: g.name || '—', cls: 'name' },
          { v: (g.utilization || 0).toFixed(0) + '%', cls: 'hl' },
          { v: (g.temperature || 0).toFixed(0) + '°', cls: '' },
          { v: fmtGb(g.memoryUsed || 0) + '/' + fmtGb(g.memoryTotal || 0), cls: '' },
          { v: (g.powerDraw || 0).toFixed(0) + 'W', cls: '' },
        ];
        cells.forEach((c) => {
          const td = document.createElement('td');
          if (c.cls) td.className = c.cls;
          td.textContent = c.v;
          tr.appendChild(td);
        });
        tbody.appendChild(tr);
      });
      table.append(thead, tbody);
      refs.gpus.appendChild(table);
    } else {
      const none = document.createElement('div');
      none.className = 'gpu-title';
      none.textContent = '无 GPU 数据';
      refs.gpus.appendChild(none);
    }

    // ---- 自定义指标：内联 key:value 紧凑块 ----
    refs.customs.innerHTML = '';
    if (frame.metrics && Object.keys(frame.metrics).length) {
      Object.entries(frame.metrics).forEach(([k, v]) => {
        const span = document.createElement('span');
        span.className = 'cmetric';
        const kv = document.createElement('span');
        kv.className = 'kv';
        kv.textContent = k + ':';
        const vv = document.createElement('span');
        vv.className = 'vv';
        vv.textContent = Number.isFinite(v) ? v.toFixed(1) : '--';
        span.append(kv, vv);
        refs.customs.appendChild(span);
      });
    }

    sortCompactCards();   // 条数可能变化，重排使同条数卡片同行
  }

  /* ---------- 条形图视图：同服务器一个图，图内按指标分组，只显示勾选指标 ---------- */
  // 每个指标的顶部刻度（顶部区间）按指标分别设置显示方式：
  // - 利用率/显存：固定 0–100%（各卡、各服务器绝对可比）
  // - 温度：按该服务器实际峰值向上取整到 10°C（最低 90°C）
  // - 功耗：按该服务器实际峰值向上取整到 100W（最低 200W），适配 600W+ 高功耗卡
  // 图表化呈现：共用基线 + 顶部 100% 参考刻度线，数值紧贴柱顶居中（随柱高浮动、不占布局）
  const BAR_CAPS = [
    // val：柱顶数值（无单位，随柱宽缩放）；tip：悬停完整值（带单位）
    { key: 'util', title: '利用率',
      scale: () => ({ max: 100, label: '0–100%' }),
      frac: (g, max) => (g.utilization || 0) / max,
      val: (g) => (g.utilization || 0).toFixed(0),
      tip: (g) => (g.utilization || 0).toFixed(0) + '%' },
    { key: 'temp', title: '温度',
      scale: (gpus) => {
        const peak = Math.max(0, ...gpus.map((g) => g.temperature || 0));
        const m = Math.max(90, Math.ceil(peak / 10) * 10);
        return { max: m, label: '0–' + m + '°C' };
      },
      frac: (g, max) => (g.temperature || 0) / max,
      val: (g) => (g.temperature || 0).toFixed(0),
      tip: (g) => (g.temperature || 0).toFixed(0) + '°' },
    { key: 'mem', title: '显存',
      scale: () => ({ max: 100, label: '0–100%' }),
      frac: (g) => (g.memoryTotal ? (g.memoryUsed || 0) / g.memoryTotal : 0),
      val: (g) => Math.round((g.memoryUsed || 0) / 1024),
      tip: (g) => Math.round((g.memoryUsed || 0) / 1024) + 'G' },
    { key: 'power', title: '功耗',
      scale: (gpus) => {
        const peak = Math.max(0, ...gpus.map((g) => g.powerDraw || 0));
        const m = Math.max(200, Math.ceil(peak / 100) * 100);
        return { max: m, label: '0–' + m + 'W' };
      },
      frac: (g, max) => (g.powerDraw || 0) / max,
      val: (g) => (g.powerDraw || 0).toFixed(0),
      tip: (g) => (g.powerDraw || 0).toFixed(0) + 'W' },
  ];

  // 一次性构建图表 DOM（含图例），返回便于就地更新的节点引用
  function buildBarChart(gpus, caps, refs) {
    const colorOf = (gi) => GPU_COLORS[gi % GPU_COLORS.length];
    const n = gpus.length;
    const gap = n > 4 ? 3 : 6;                                            // 多卡时收紧柱间距
    const barW = Math.max(10, Math.min(26, Math.floor((104 - (n - 1) * gap) / n))); // 柱宽随卡数自适应
    const valFont = Math.max(6, Math.min(9, Math.round(barW * 0.38)));    // 柱顶文字随柱宽缩放（6-9px，细字号不抢柱体）

    const container = document.createElement('div');
    container.className = 'barchart2';
    container.style.gridTemplateColumns = 'repeat(' + caps.length + ', 1fr)'; // 勾选指标数决定列数

    const groups = [];
    const heads = [];
    caps.forEach((grp) => {
      const gdiv = document.createElement('div');
      gdiv.className = 'bc2-group';
      const head = document.createElement('div');
      head.className = 'bc2-head';
      const title = document.createElement('span');
      title.className = 'bc2-title';
      title.textContent = grp.title;
      const cap = document.createElement('span');
      cap.className = 'bc2-cap';
      cap.textContent = grp.scale(gpus).label;   // 初始顶部区间标签
      head.append(title, cap);

      const bars = document.createElement('div');
      bars.className = 'bc2-bars';
      bars.style.columnGap = gap + 'px';
      const defs = [];
      for (let j = 0; j < n; j++) {
        const bar = document.createElement('div');
        bar.className = 'bc2-bar';
        bar.style.width = barW + 'px';
        const fill = document.createElement('div');
        fill.className = 'bc2-fill';
        fill.style.background = colorOf(j);
        const val = document.createElement('b');
        val.className = 'bc2-val';
        val.style.fontSize = valFont + 'px';
        val.textContent = '—';
        fill.appendChild(val);
        bar.appendChild(fill);
        bars.appendChild(bar);
        defs.push({ bar, fill, val });
      }
      gdiv.append(head, bars);
      container.appendChild(gdiv);
      groups.push(defs);
      heads.push(cap);
    });

    // 图例：色块 + GPU 编号，悬浮显示型号
    const legend = document.createElement('div');
    legend.className = 'bc2-legend';
    gpus.forEach((g, gi) => {
      const it = document.createElement('span');
      const sw = document.createElement('i');
      sw.style.background = colorOf(gi);
      const t = document.createElement('span');
      t.textContent = 'GPU ' + g.index;
      it.title = g.name || 'GPU ' + g.index;
      it.append(sw, t);
      legend.appendChild(it);
    });

    refs.gpus.appendChild(container);
    refs.gpus.appendChild(legend);
    return { chart: container, n, caps: caps.length, groups, heads, spacing: barW + gap }; // spacing 供重叠判定
  }

  function renderBar(s, frame) {
    const refs = s.refs;
    refs.el.classList.toggle('off', !s.online);
    const gpus = (frame.gpus && frame.gpus.length) ? frame.gpus : [];
    refs.count.textContent = gpus.length ? gpus.length + ' GPU' : '';
    const caps = BAR_CAPS.filter((c) => barMetrics.has(c.key)); // 勾选决定显示的指标组

    if (gpus.length === 0 || caps.length === 0) {
      refs.gpus.innerHTML = '';
      s.bar = null;
      const none = document.createElement('div');
      none.className = 'gpu-title';
      none.textContent = caps.length === 0 ? '未勾选指标' : '无 GPU 数据';
      refs.gpus.appendChild(none);
    } else {
      // GPU 数量、勾选指标数变化、或图表被其他视图移除时重建；否则就地更新（平滑过渡、避免整卡闪烁）
      if (!s.bar || s.bar.n !== gpus.length || s.bar.caps !== caps.length || !refs.gpus.contains(s.bar.chart)) {
        refs.gpus.innerHTML = '';   // 先清空旧内容（标准/精简残留或旧图表），避免叠加
        s.bar = buildBarChart(gpus, caps, refs);
      }
      caps.forEach((grp, gi) => {
        const sc = grp.scale(gpus);                    // 该指标顶部刻度（温度/功耗按实际峰值动态取整）
        s.bar.heads[gi].textContent = sc.label;        // 顶部区间标签实时更新
        gpus.forEach((g, j) => {
          const node = s.bar.groups[gi][j];
          node.val.style.display = '';   // 先恢复可见再测量
          node.fill.style.height = Math.max(0, Math.min(100, grp.frac(g, sc.max) * 100)).toFixed(1) + '%';
          node.val.textContent = grp.val(g);
          node.bar.title = 'GPU ' + g.index + (g.name ? ' · ' + g.name : '') + '\n' + grp.title + ': ' + grp.tip(g);
        });
        // 最终兜底：数值文字实测宽度 > 柱宽+间距 即与相邻柱重叠/拥挤 → 取消该柱数值显示
        // （柱体与悬停 tooltip 的完整值均保留；offsettWidth 为 0 表示未布局，跳过不误杀）
        s.bar.groups[gi].forEach((node) => {
          const w = node.val.offsetWidth;
          if (w > 0 && w > s.bar.spacing) node.val.style.display = 'none';
        });
      });
    }

    // 自定义指标：内联紧凑块（与精简视图一致）
    refs.customs.innerHTML = '';
    if (frame.metrics && Object.keys(frame.metrics).length) {
      Object.entries(frame.metrics).forEach(([k, v]) => {
        const span = document.createElement('span');
        span.className = 'cmetric';
        const kv = document.createElement('span');
        kv.className = 'kv';
        kv.textContent = k + ':';
        const vv = document.createElement('span');
        vv.className = 'vv';
        vv.textContent = Number.isFinite(v) ? v.toFixed(1) : '--';
        span.append(kv, vv);
        refs.customs.appendChild(span);
      });
    }
  }

  /* ---------- 表格视图：所有服务器所有信息汇总到同一个宽表（点击表头排序） ---------- */
  const BASE_COLS = [
    { key: 'status', label: '状态' },
    { key: 'server', label: '服务器' },
    { key: 'gpu', label: 'GPU' },
    { key: 'name', label: '型号' },
    { key: 'util', label: '利用率' },
    { key: 'temp', label: '温度' },
    { key: 'mem', label: '显存' },
    { key: 'power', label: '功耗' },
    { key: 'seen', label: '最近上报' },
  ];
  let tableSort = { key: null, asc: true };   // 当前排序列与方向

  function sortBy(key) {
    if (tableSort.key === key) tableSort.asc = !tableSort.asc;
    else tableSort = { key, asc: true };
    renderTable();
  }

  // 按列取两行原始可比值，供排序比较（缺失/空值统一排后）
  function compareRow(a, b, key) {
    switch (key) {
      case 'status': return (a.online === b.online) ? 0 : (a.online ? -1 : 1);
      case 'server': return String(a.server).localeCompare(String(b.server));
      case 'gpu':    return (a.gpuIndex || 0) - (b.gpuIndex || 0);
      case 'name':   return String(a.name || '').localeCompare(String(b.name || ''));
      case 'util':   return (a.util || 0) - (b.util || 0);
      case 'temp':   return (a.temp || 0) - (b.temp || 0);
      case 'mem':    return (a.memUsed || 0) - (b.memUsed || 0);
      case 'power':  return (a.power || 0) - (b.power || 0);
      case 'seen':   return a.seen - b.seen;
      default: { // 自定义指标列
        const av = a.metric[key], bv = b.metric[key];
        const hasA = Number.isFinite(av), hasB = Number.isFinite(bv);
        if (hasA && hasB) return av - bv;
        if (hasA) return -1;
        if (hasB) return 1;
        return 0;
      }
    }
  }

  function renderTable() {
    tableView.innerHTML = '';
    if (!servers.size) return;

    // 自定义指标列的并集（跨服务器收集所有出现的 metric key）
    const metricKeys = new Set();
    servers.forEach((s) => {
      const m = (s.frame && s.frame.metrics) || {};
      Object.keys(m).forEach((k) => metricKeys.add(k));
    });
    const keys = [...metricKeys];
    const cols = [...BASE_COLS, ...keys.map((k) => ({ key: k, label: k }))];

    // 构建行数据（含原始排序值），再按当前排序列排序
    const rows = [];
    servers.forEach((s, id) => {
      const gpus = (s.frame && s.frame.gpus && s.frame.gpus.length) ? s.frame.gpus : [null];
      gpus.forEach((g) => {
        rows.push({
          online: s.online, server: id, seen: s.lastSeen,
          g, gpuIndex: g ? g.index : null,
          name: g ? g.name : '', util: g ? g.utilization : 0,
          temp: g ? g.temperature : 0, memUsed: g ? g.memoryUsed : 0,
          power: g ? g.powerDraw : 0,
          metric: (s.frame && s.frame.metrics) || {},
        });
      });
    });
    if (tableSort.key) {
      const { key, asc } = tableSort;
      rows.sort((a, b) => { const r = compareRow(a, b, key); return asc ? r : -r; });
    }

    const table = document.createElement('table');
    table.className = 'big-table';
    const thead = document.createElement('thead');
    const hr = document.createElement('tr');
    cols.forEach((col) => {
      const th = document.createElement('th');
      th.classList.add('sortable');
      th.textContent = col.label + (tableSort.key === col.key ? (tableSort.asc ? ' ▲' : ' ▼') : '');
      th.onclick = () => sortBy(col.key);
      hr.appendChild(th);
    });
    thead.appendChild(hr);

    const tbody = document.createElement('tbody');
    rows.forEach((row) => {
      const tr = document.createElement('tr');
      if (!row.online) tr.className = 'off';
      const stTd = document.createElement('td');
      stTd.textContent = row.online ? '在线' : '离线';
      stTd.className = row.online ? 'st-on' : 'st-off';
      tr.appendChild(stTd);
      const g = row.g;
      [row.server,
        g ? '#' + g.index : '—',
        g ? (g.name || '—') : '—',
        g ? (g.utilization || 0).toFixed(0) + '%' : '—',
        g ? (g.temperature || 0).toFixed(0) + '°' : '—',
        g ? fmtGb(g.memoryUsed || 0) + '/' + fmtGb(g.memoryTotal || 0) : '—',
        g ? (g.powerDraw || 0).toFixed(0) + 'W' : '—',
        ago(row.seen),
      ].forEach((v) => { const td = document.createElement('td'); td.textContent = v; tr.appendChild(td); });
      keys.forEach((k) => {
        const td = document.createElement('td');
        const v = row.metric[k];
        td.textContent = Number.isFinite(v) ? v.toFixed(1) : '—';
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    table.append(thead, tbody);
    tableView.appendChild(table);
  }

  function tempColor(t) { return t >= 80 ? 'red' : t >= 60 ? 'orange' : 'green'; }

  /* ---------- 单个 Canvas 仪表 ---------- */
  function makeGauge(sp) {
    const cell = document.createElement('div');
    cell.className = 'gauge-cell';

    const canvas = document.createElement('canvas');
    const dpr = window.devicePixelRatio || 1;
    const size = 96;
    canvas.width = size * dpr;
    canvas.height = size * dpr;
    canvas.style.width = size + 'px';
    canvas.style.height = size + 'px';

    const label = document.createElement('div');
    label.className = 'gauge-label';
    label.textContent = sp.name;
    const val = document.createElement('div');
    val.className = 'gauge-val';
    val.textContent = '--';

    cell.append(canvas, label, val);
    return { cell, canvas, val, cur: 0, target: 0, max: sp.max, unit: sp.unit, fixed: sp.fixed, color: sp.color };
  }

  /* ---------- Canvas 环形仪表绘制 ---------- */
  function drawGauge(gauge) {
    const canvas = gauge.canvas;
    const ctx = canvas.getContext('2d');
    const dpr = window.devicePixelRatio || 1;
    const size = 96;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, size, size);

    const cx = size / 2, cy = size / 2;
    const r = size / 2 - 9;
    const start = Math.PI * 0.75;   // 左下 135°
    const end = Math.PI * 2.25;     // 右下 405°，共 270°

    ctx.lineCap = 'round';
    // 背景轨道
    ctx.strokeStyle = COLORS.track;
    ctx.lineWidth = 8;
    ctx.beginPath();
    ctx.arc(cx, cy, r, start, end);
    ctx.stroke();
    // 数值弧
    const pct = Math.max(0, Math.min(1, (gauge.cur || 0) / (gauge.max || 1)));
    ctx.strokeStyle = COLORS[gauge.color] || COLORS.cyan;
    ctx.beginPath();
    ctx.arc(cx, cy, r, start, start + (end - start) * pct);
    ctx.stroke();
  }

  function fmtGaugeVal(gauge) {
    const v = gauge.target || 0;
    if (gauge.unit === 'GB') {
      return (v / 1024).toFixed(1) + ' / ' + ((gauge.max || 0) / 1024).toFixed(0) + ' GB';
    }
    return v.toFixed(gauge.fixed || 0) + ' ' + gauge.unit;
  }

  /* ---------- 动画循环：仪表平滑插值 + 每秒状态刷新 ---------- */
  requestAnimationFrame(tick);
  function tick() {
    for (const g of allGauges.slice()) {
      const next = g.cur + (g.target - g.cur) * 0.18;
      g.cur = Math.abs(g.target - next) < 0.01 ? g.target : next;
      drawGauge(g);
      g.val.textContent = fmtGaugeVal(g);
    }
    requestAnimationFrame(tick);
  }

  setInterval(() => {
    servers.forEach((s) => {
      const refs = s.refs;
      refs.el.classList.toggle('off', !s.online);
      refs.dot.classList.toggle('on', s.online);
      refs.seen.textContent = s.online ? '最近上报 ' + ago(s.lastSeen) : '离线 ' + ago(s.lastSeen);
    });
  }, 1000);

  function ago(ts) {
    const ms = Math.max(0, Date.now() - ts);
    if (ms < 1000) return '刚刚';
    return Math.round(ms / 1000) + ' 秒前';
  }

  setView(viewMode); // 应用持久化的视图模式（按钮高亮 + body.compact）
  connect();
})();
