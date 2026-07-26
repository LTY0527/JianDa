FACT_FEW_SHOTS = """
示例一（字段缺失）：
原文只写“周三下午在松林活动室开展讲座”。
可提取 EVENT_DATE 或 SERVICE_TIME、LOCATION；CONTACT、FEE、MATERIAL 不存在，
应视为 null；本 Schema 使用“省略该字段项”的等价表示，不得补写电话或材料。

示例二（冲突信息）：
同一材料分别写“报名截止5月10日”和“报名截止5月12日”。
保留两个 END_DATE 项，各自引用原句，降低 confidence，并在 label 中注明“待人工核对”。

示例三（不同公共服务）：
原文写“申请人携带身份证到清河服务窗口，咨询电话010-55556666”。
只提取 MATERIAL、LOCATION、CONTACT，数字与地址保持原文精度。
"""
