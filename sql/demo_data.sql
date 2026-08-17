-- ============================================================
-- TicketAI 演示数据（DEV_DOC §8 M8）
-- 生成：100 张工单（最近 14 天分布，含按时/超时 SLA 样本）+ 20 篇知识库文章
-- 用法: mysql -uroot -p1234 ticket_ai < demo_data.sql
-- 注意: 仅执行一次（ticket_no 唯一约束，重复执行会冲突中断）；知识库文章的 ES 索引需通过 API 重新创建才会索引（检索演示用 API 建的已有文章）
-- ============================================================

USE ticket_ai;

-- 仅在 ticket 为空时生成，避免重复执行污染
SET @ticket_count = (SELECT COUNT(*) FROM ticket);
SET @kb_count = (SELECT COUNT(*) FROM knowledge_base);

-- ---------- 1. 工单（100 张，递归 CTE 生成） ----------
INSERT INTO ticket (
    ticket_no, channel_id, title, description, category, priority, status,
    agent_id, customer_name, customer_contact, sla_policy_id,
    first_responded_at, resolved_at, closed_at, version, create_by, create_time, update_time
)
WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 100)
SELECT
    CONCAT('T', DATE_FORMAT(DATE_SUB(NOW(), INTERVAL (n % 14) DAY), '%Y%m%d'), LPAD(n, 6, '0')),
    1,
    CONCAT('演示工单-', n, '-',
        ELT(1 + (n % 5), '商品质量问题', '物流延迟投诉', '退款申请', '发票开具咨询', '售后维修预约')),
    CONCAT('这是演示数据生成的工单描述，用于展示系统各状态流转。编号 #', n,
           '，模拟客户反馈：', ELT(1 + (n % 5), '产品存在瑕疵', '快递长时间未更新', '希望尽快退款', '需要开具增值税发票', '预约上门维修')),
    ELT(1 + (n % 5), '售后', '投诉', '售后', '咨询', '售前'),
    1 + (n % 4),
    -- 状态分布：0-取消 1-待分派 2-待分派 3-处理中 4-处理中 5-等待客户 6-已解决 7-已解决 8-已关闭 9-已升级
    ELT(1 + (n % 10), 8, 2, 2, 3, 3, 4, 5, 5, 6, 7),
    CASE WHEN (n % 10) IN (3, 4, 5, 9) THEN 1 ELSE NULL END,
    CONCAT('演示客户', n),
    CONCAT('13', LPAD(n % 100000000, 8, '0')),
    1 + (n % 4),
    -- 按时样本（n%3!=0）：首次响应=创建后 30 分钟；超时样本（n%3==0）：无响应（留空）
    CASE WHEN (n % 10) IN (3, 4, 5, 6, 7, 8, 9) AND (n % 3) != 0
         THEN DATE_ADD(DATE_SUB(NOW(), INTERVAL (n % 14) DAY), INTERVAL 30 MINUTE) ELSE NULL END,
    -- 已解决/已关闭：解决时间=创建后 4 小时
    CASE WHEN (n % 10) IN (6, 7, 8)
         THEN DATE_ADD(DATE_SUB(NOW(), INTERVAL (n % 14) DAY), INTERVAL 4 HOUR) ELSE NULL END,
    CASE WHEN (n % 10) IN (8) OR (n % 10) = 0
         THEN DATE_ADD(DATE_SUB(NOW(), INTERVAL (n % 14) DAY), INTERVAL 6 HOUR) ELSE NULL END,
    0, 'demo', DATE_SUB(NOW(), INTERVAL (n % 14) DAY), DATE_SUB(NOW(), INTERVAL (n % 14) DAY)
FROM seq;

-- 升级工单补充升级时间（状态 7）
UPDATE ticket SET update_time = NOW() WHERE status = 7 AND create_by = 'demo';

-- ---------- 2. 工单 SLA 实例（与演示工单一一对应） ----------
INSERT INTO ticket_sla (
    ticket_id, sla_policy_id, first_response_deadline, resolve_deadline,
    first_responded_at, resolved_at, first_response_status, resolve_status,
    escalation_triggered, escalated_at, create_time, update_time
)
SELECT
    t.id,
    t.sla_policy_id,
    DATE_ADD(t.create_time, INTERVAL (t.priority * 60) MINUTE),
    DATE_ADD(t.create_time, INTERVAL (t.priority * 240) MINUTE),
    t.first_responded_at,
    t.resolved_at,
    -- 超时样本（n%3==0 且未响应）：状态 2；按时：状态 1；未到期：0
    CASE WHEN t.first_responded_at IS NOT NULL THEN 1
         WHEN t.status IN (2, 3, 4) AND t.create_time < DATE_SUB(NOW(), INTERVAL (t.priority * 60) MINUTE) THEN 2
         ELSE 0 END,
    CASE WHEN t.resolved_at IS NOT NULL THEN 1 ELSE 0 END,
    CASE WHEN t.status = 7 THEN 1 ELSE 0 END,
    CASE WHEN t.status = 7 THEN DATE_ADD(t.create_time, INTERVAL (t.priority * 60 + 5) MINUTE) ELSE NULL END,
    t.create_time, t.update_time
FROM ticket t
WHERE t.create_by = 'demo';

-- 超时升级工单的状态流转日志（仅演示工单，避免与真实升级日志重复）
INSERT INTO ticket_status_log (ticket_id, from_status, to_status, event, operator_id, operator_type, remark, create_time)
SELECT t.id, 2, 7, 'TIMEOUT_ESCALATE', NULL, 'SYSTEM', 'SLA 超时自动升级（演示数据）', s.escalated_at
FROM ticket_sla s JOIN ticket t ON t.id = s.ticket_id
WHERE s.escalation_triggered = 1 AND t.create_by = 'demo';

-- ---------- 3. 知识库文章（20 篇） ----------
INSERT INTO knowledge_base (title, category, content, status, author_id, view_count, create_by, create_time, update_time)
VALUES
('七天无理由退货政策', '售后', '# 适用条件\n商品未拆封、不影响二次销售，自签收之日起七日内可申请。\n# 不适用情形\n定制类商品、已拆封的影音制品、贴身衣物等。', 1, 1, 156, 'demo', NOW(), NOW()),
('退款到账时间说明', '售后', '# 到账时效\n审核通过后退款原路返回，一般 1-3 个工作日。\n# 异常处理\n超过 5 个工作日未到账，请联系支付渠道客服。', 1, 1, 98, 'demo', NOW(), NOW()),
('换货流程指引', '售后', '# 换货步骤\n1. 提交换货申请；2. 寄回商品（运费说明）；3. 仓库质检；4. 发出新品。\n# 时效\n全程一般 7-10 个工作日。', 1, 1, 121, 'demo', NOW(), NOW()),
('维修服务预约', '售后', '# 预约方式\n通过在线客服或电话预约，选择上门或到店。\n# 保修说明\n保修期内非人为损坏免费维修。', 1, 1, 87, 'demo', NOW(), NOW()),
('物流发货时效查询', '售前', '# 发货时间\n现货商品 48 小时内发货，预售商品见商品页说明。\n# 物流查询\n订单详情页可实时查看物流轨迹。', 1, 1, 210, 'demo', NOW(), NOW()),
('发票开具说明', '咨询', '# 开票方式\n订单完成后可在订单详情页申请电子发票。\n# 抬头修改\n开票后如需修改抬头，需联系客服作废重开。', 1, 1, 64, 'demo', NOW(), NOW()),
('会员权益介绍', '售前', '# 会员等级\n普通/银卡/金卡/黑卡四级，消费累积成长值。\n# 权益差异\n金卡以上享受优先客服和专属折扣。', 1, 1, 45, 'demo', NOW(), NOW()),
('价格保护政策', '售后', '# 价保规则\n购买后 7 天内同款降价可申请差价退还。\n# 申请方式\n联系客服提供订单号与降价截图。', 1, 1, 133, 'demo', NOW(), NOW()),
('优惠券使用规则', '咨询', '# 使用条件\n满减券与折扣券不可叠加，限指定品类。\n# 过期处理\n优惠券过期不补发，请留意有效期。', 1, 1, 76, 'demo', NOW(), NOW()),
('投诉处理流程', '投诉', '# 受理范围\n服务质量、商品质量、物流问题等投诉。\n# 处理时效\n投诉受理后 48 小时内响应，5 个工作日内给出处理结果。', 1, 1, 54, 'demo', NOW(), NOW()),
('客服联系渠道', '咨询', '# 联系方式\n在线客服（9:00-22:00）、客服热线（9:00-18:00）、邮件支持。\n# 响应时效\n在线客服即时响应，邮件 24 小时内回复。', 1, 1, 189, 'demo', NOW(), NOW()),
('订单取消说明', '售前', '# 取消条件\n未发货订单可自助取消，已发货需拒收或退货。\n# 退款时效\n取消后 1-3 个工作日原路退回。', 1, 1, 92, 'demo', NOW(), NOW()),
('商品验收注意事项', '售后', '# 签收检查\n请当面检查外包装与商品，破损可拒收。\n# 证据留存\n保留开箱视频/照片，便于售后处理。', 1, 1, 71, 'demo', NOW(), NOW()),
('账号安全建议', '咨询', '# 密码管理\n定期修改密码，勿与其他平台共用。\n# 异常登录\n发现异常立即修改密码并联系客服。', 1, 1, 38, 'demo', NOW(), NOW()),
('支付方式说明', '售前', '# 支持方式\n微信、支付宝、银行卡、货到付款。\n# 分期服务\n支持部分银行信用卡 3/6/12 期免息。', 1, 1, 85, 'demo', NOW(), NOW()),
('赠品发放规则', '售后', '# 发放条件\n活动赠品随主商品发出，数量有限。\n# 补发说明\n漏发赠品可联系客服核实补发。', 1, 1, 29, 'demo', NOW(), NOW()),
('退货运费承担', '售后', '# 质量原因\n商品质量问题退货运费由商家承担。\n# 无理由退货\n非质量问题，运费由买家承担（有运费险除外）。', 1, 1, 104, 'demo', NOW(), NOW()),
('售后时效承诺', '售后', '# 响应承诺\n售后工单 2 小时内首次响应，48 小时内给出方案。\n# 监督渠道\n超时可拨打客服热线投诉催办。', 1, 1, 67, 'demo', NOW(), NOW()),
('新品上架预告', '售前', '# 上新节奏\n每周三新品上架，会员可提前 24 小时购买。\n# 预售说明\n预售商品按页面标注时间发货。', 1, 1, 42, 'demo', NOW(), NOW()),
('常见问题速查', '咨询', '# 高频问题\n发货时间、退款时效、发票、运费、会员权益等常见问题速查表。\n# 找不到答案\n请提交工单，客服将在 2 小时内响应。', 1, 1, 233, 'demo', NOW(), NOW());

SELECT CONCAT('演示数据生成完成：工单 ', @ticket_count, ' -> ', (SELECT COUNT(*) FROM ticket),
              ' 张，文章 ', @kb_count, ' -> ', (SELECT COUNT(*) FROM knowledge_base), ' 篇') AS result;
