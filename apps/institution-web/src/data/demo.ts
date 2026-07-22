export const documents = [
  {
    id: 1001,
    title: "老年补贴申请指南",
    fileName: "浦江街道老年补贴办理通知.pdf",
    organization: "浦江街道社区服务中心",
    status: "WAITING_REVIEW",
    statusText: "待审核",
    progress: 100,
    updatedAt: "2026-07-22 09:40",
  },
  {
    id: 1002,
    title: "医院门诊就医流程",
    fileName: "门诊就医流程图.png",
    organization: "城市人民医院",
    status: "PROCESSING",
    statusText: "处理中",
    progress: 68,
    updatedAt: "2026-07-22 09:18",
  },
  {
    id: 1003,
    title: "社区养老服务申请",
    fileName: "社区养老服务申请.pdf",
    organization: "浦江街道社区服务中心",
    status: "PUBLISHED",
    statusText: "已发布",
    progress: 100,
    updatedAt: "2026-07-21 16:35",
  },
  {
    id: 1004,
    title: "反诈提醒：警惕养老投资骗局",
    fileName: "反诈宣传通知.jpg",
    organization: "简达平台运营中心",
    status: "PUBLISHED",
    statusText: "已发布",
    progress: 100,
    updatedAt: "2026-07-20 11:05",
  },
];

export const fields = [
  {
    id: 1,
    label: "适用对象",
    value: "具有本市户籍、年满 80 周岁的老年人",
    page: 1,
    quote: "补贴对象为具有本市户籍且年满八十周岁的老年人。",
    confidence: 0.98,
  },
  {
    id: 2,
    label: "申请条件",
    value: "申请人当前未享受同类生活补贴",
    page: 1,
    quote: "已享受同类补贴待遇的，不重复发放。",
    confidence: 0.91,
  },
  {
    id: 3,
    label: "所需材料",
    value: "身份证、户口簿、本人银行卡、近期一寸照片",
    page: 2,
    quote:
      "申请材料：身份证及户口簿原件、本人银行卡复印件、近期一寸免冠照片一张。",
    confidence: 0.97,
  },
  {
    id: 4,
    label: "办理地点",
    value: "户籍所在地社区服务窗口",
    page: 2,
    quote: "请申请人至户籍所在地社区服务窗口提出申请。",
    confidence: 0.96,
  },
  {
    id: 5,
    label: "联系电话",
    value: "021-12345",
    page: 3,
    quote: "咨询电话：021-12345，工作日 9:00—17:00。",
    confidence: 0.99,
  },
];

export const steps = [
  ["准备材料", "带好身份证、户口簿、银行卡和一寸照片。"],
  ["到社区申请", "前往户籍所在地社区服务窗口，领取申请表。"],
  ["填写并提交", "如实填写信息，交给窗口工作人员核对。"],
  ["等待审核", "一般 10 个工作日内完成审核，请保持电话畅通。"],
  ["查询结果", "审核通过后，补贴将按规定发放到本人银行卡。"],
];
