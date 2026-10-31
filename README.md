# ai-workspace-backend

## 帖子详情与公开读取

- `GET /api/posts?sortBy=&cursor=&limit=` 公开读取已发布且未删除的帖子；排序支持 `publishedAt`、`upVoteCount`、`commentCount`，游标原样续传，默认 20、上限 50。
- `GET /api/posts/{publicId}` 公开读取已发布帖子；有效 cookie 会被尽力解析，使作者可以读取自己的草稿。游客、其它账户读取草稿，以及任意人读取已删除或不存在的帖子，均为 `404 POST_NOT_FOUND`。
- `GET /api/posts/{publicId}/comments?cursor=&limit=` 共享详情可见性，草稿评论仅作者可读，不通过评论或空列表确认他人草稿存在。已发布评论仍可由游客读取。
- `POST /api/posts`（201）与 `PUT /api/posts/{publicId}`（200）均返回与详情 GET 相同的白名单：`publicId/title/content/coverImage/tags/status/authorPublicId/createdAt/updatedAt/commentCount/upVoteCount/bookmarkCount`。
- 三项统计均为非负整数，新帖为 0；分别计未软删除评论、有效赞成票、有效收藏。反对票不计入 `upVoteCount`，取消反对票不扣赞成数。详情和列表共享计数口径，公开响应不包含本人票向或收藏状态。
- 响应不包含实体、内部主键、删除时间、版本、内部创建人/更新人以及内部排序时间 `publishedAt`。
- 本次可见性收口只用于读取；既有写端的来源校验、认证、作者约束及草稿写规则不变。前端草稿只读不代表后端新增写权限限制。

本组验收对应 CHANGE `add-wanderchain-post-interactions-frontend` 的 `wanderchain-posts` / `wanderchain-post-interactions` delta：
- `PostServiceTest.detailShapesCarryZeroCounts`、`detailCountsExcludeDeletedInteractionsAndDownVotes`：创建/读取/编辑统计、软删除排除与反对票口径。
- `PostControllerTest.responseFieldsMatchWhitelist`、`detailJsonCarriesEffectiveCounts`：实际 HTTP JSON 的同形白名单与统计，不以 DTO 反射代替端点结果。
- `PostInteractionServiceTest.commentReadVisibilityMatchesDetail`、`PostInteractionControllerTest.attachedReadsShareDraftVisibility`：已发布公开、作者草稿、游客/他人/无效 cookie、已删除/不存在及读取与写入边界。

## 本人互动状态与评论关系

- `GET /api/posts/{publicId}/interactions/me` 不属于公开路由，仅接受认证 cookie 确认的令牌账户；客户端 `accountId/accountPublicId` 不影响主体，Authorization 头不能替代 cookie。
- 成功为 200，准确返回 `voteDirection`（`up/down/null`）与 `bookmarked`（boolean）两字段，并带 `Cache-Control: no-store`；无有效本人记录为 null/false，不是 404。取消后的软删除记录不计入状态，A/B 互不串用。
- 本人状态先认证后判断帖子可见性：无有效令牌始终 401 `UNAUTHENTICATED`；认证后他人草稿、已删除或不存在帖子为 404 `POST_NOT_FOUND`，作者草稿可以读取。未登记的更深 GET 默认受保护，既有认证端点登记不变。
- 评论创建与列表均使用 `parentPublicId`，根评论为 null。列表批量解析真实父标识，同页、跨页与软删除父均保留关系；已删除父正文不回显，根删除不级联删除回复。既有服务端不限制回复深度，本次不新增限制或清理历史深层回复。
- 评论仍按 `createdAt desc, id desc` 稳定续页，默认 20、上限 50、非法游标为 400 `VALIDATION_FAILED`，末页 `nextCursor=null`。内容按 Java `String.trim()`（首尾 U+0000–U+0020）处理后长度 1–2000 个 UTF-16 代码单元，非 BMP 字符占两个代码单元，NBSP 不被 trim。
- 评论 POST 为 201，投票/收藏首次 POST 为 201、重复为 200，合法本人 DELETE 为 204。本人无有效投票/收藏但存在他人有效记录时取消为 403 `POST_FORBIDDEN`；完全无有效记录时为 404 `POST_NOT_FOUND`，不能当作幂等成功。取消 UP 减赞成数，取消 DOWN 不改变赞成数。
- 所有写端保留来源先于认证的次序：来源缺失/不可信为 403 `CROSS_SITE_REJECTED`，同源但无有效 cookie 为 401。本次复用原有表、错误码和写权限，不新增数据库结构。

本组验收对应互动 delta：
- `PostInteractionServiceTest.ownStateIsEffectiveAndAccountIsolated/ownStateUsesPostReadVisibility` 与 `PostInteractionControllerTest.personalStateJsonIsPrivateAndAccountBound/personalStateAuthenticatesBeforeVisibility`：本人状态、准确 JSON 白名单、缓存头、A/B 隔离、认证优先与可见性。
- service `commentListResolvesRealParentsAcrossPagesAndDeletion/invalidReplyTargetsDoNotCreateComments` 与 controller `replyListJsonResolvesParents`：实际列表父映射、跨页、父删除、深层保留及无效目标。
- service `commentPagingLimitsAndStableContinuation/commentContentUsesJavaTrimAndUtf16Length` 与 controller `commentPaginationJsonContract/commentUtf16JsonContract/deleteGuardsAndCancelErrorsRemainUnchanged`：稳定分页、UTF-16 边界与来源/认证/取消错误。

局部测试可通过既有凭据注入闸门执行（仅使用进程环境，结束恢复 `MAVEN_ARGS`，不记录凭据）：

```powershell
$previous = $env:MAVEN_ARGS
try {
    $env:MAVEN_ARGS = '-Dtest=PostServiceTest,PostControllerTest,PostInteractionServiceTest,PostInteractionControllerTest'
    powershell -NoProfile -File harness/checks/02-test.ps1
    if ($LASTEXITCODE -ne 0) { throw '帖子端点测试失败' }
} finally {
    $env:MAVEN_ARGS = $previous
}
```

以上命令从工作区根执行；直接运行 Maven 时需自行准备 DB、Redis 与测试 JWT 环境。局部通过不代替工作区总 Harness 验收。