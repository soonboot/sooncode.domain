// ============================================================
// find-duplicate-keys.js —— Mongo 重复键查询脚本 (mongosh 专用)
// ============================================================
//
// 功能：
//   1) 通用：查任意集合、任意(单/复合)字段的重复值
//   2) 本项目一键检查：sooncode.domain 的 3 个核心唯一索引
//        - eventMetadata : { id: 1 } unique
//        - eventSource   : { streamId: 1, version: 1 } unique
//        - eventSnapshot : { streamId: 1 } unique
//   3) 自动扫描：查某集合上所有 unique 索引是否有重复数据
//      (建唯一索引前必跑，对应 Java 里 MongoIndexInitializer.ensureIndex 的逻辑)
//
// ---- 用法 A：直接粘贴到 mongosh / Compass Shell ----
//   use("myDatabase");
//   load("scripts/find-duplicate-keys.js");   // 或者直接粘贴全文
//   findDuplicateKeys("user", ["email"]);
//   findDuplicateKeys("order", ["userId", "orderNo"], { limit: 20 });
//   checkSooncodeCore();                       // 本项目一键检查
//   checkUniqueIndexes("user");                // 扫描 user 表所有唯一索引
//   checkDb();                                 // 扫描当前库所有集合的唯一索引
//
// ---- 用法 B：命令行一键执行 ----
//   mongosh "mongodb://localhost:27017/myDatabase" --file scripts/find-duplicate-keys.js
//   # 只查指定表+字段（不需要改文件，用 --eval 传参）：
//   mongosh "mongodb://localhost:27017/myDatabase" \
//     --eval 'var TARGET_COLLECTION="user"; var TARGET_KEYS=["email"]' \
//     --file scripts/find-duplicate-keys.js
//
//   # 查复合键：
//   mongosh "mongodb://localhost:27017/myDatabase" \
//     --eval 'var TARGET_COLLECTION="eventSource"; var TARGET_KEYS=["streamId","version"]' \
//     --file scripts/find-duplicate-keys.js
//
// ---- 用法 C：最简聚合（不想加载脚本时，复制这段改表名/字段名即可） ----
//   db.user.aggregate([
//     { $group: { _id: "$email", count: { $sum: 1 }, sampleIds: { $push: "$_id" } } },
//     { $match: { count: { $gt: 1 } } },
//     { $sort: { count: -1 } },
//     { $limit: 20 }
//   ]);
//
// ============================================================

/* ---------------- 核心函数：查重复 ---------------- */

/**
 * 查指定集合上指定字段(单字段或复合字段)的重复值
 *
 * @param {string} collName  集合名，如 "user"
 * @param {string|string[]} keyFields  要判重的字段，如 "email" 或 ["streamId","version"]，支持点号 "a.b"
 * @param {object} [opts]
 * @param {number} [opts.limit=20]        最多返回多少组重复
 * @param {number} [opts.sampleSize=5]    每组最多展示几个 _id 样本
 * @param {boolean} [opts.showSamples=true] 是否展示样本 _id
 * @returns {Array} 重复组列表（每组含 key 值、count、sampleIds）
 */
function findDuplicateKeys(collName, keyFields, opts) {
  opts = opts || {};
  var limit = opts.limit != null ? opts.limit : 20;
  var sampleSize = opts.sampleSize != null ? opts.sampleSize : 5;
  var showSamples = opts.showSamples !== false;

  if (typeof keyFields === "string") keyFields = [keyFields];
  if (!Array.isArray(keyFields) || keyFields.length === 0) {
    print("❌ keyFields 不能为空，示例：findDuplicateKeys(\"user\", [\"email\"])");
    return [];
  }

  var coll = db.getCollection(collName);
  if (!coll) {
    print("❌ 集合不存在：" + collName);
    return [];
  }

  // 兼容字段值为缺失/数组等情况：group 的 _id 用 $field 引用即可
  var groupId = {};
  keyFields.forEach(function (f) { groupId[f] = "$" + f; });
  // 单字段时展平显示更直观：_id 直接是值而不是 {email: xxx}
  var isSingle = keyFields.length === 1;

  var pipeline = [
    {
      $group: {
        _id: isSingle ? ("$" + keyFields[0]) : groupId,
        count: { $sum: 1 },
        sampleIds: { $push: "$_id" }
      }
    },
    { $match: { count: { $gt: 1 } } },
    { $sort: { count: -1 } },
    { $limit: limit }
  ];

  var rows;
  try {
    rows = coll.aggregate(pipeline, { allowDiskUse: true }).toArray();
  } catch (e) {
    print("❌ [" + collName + "] 聚合查询失败：" + e);
    return [];
  }

  // 截断样本，避免刷屏
  rows = rows.map(function (r) {
    return {
      key: r._id,
      keyFields: keyFields,
      count: r.count,
      sampleIds: showSamples ? r.sampleIds.slice(0, sampleSize) : undefined,
      totalSampleIds: r.sampleIds.length
    };
  });

  print("—— [" + db.getName() + "." + collName + "] 判重字段: [" + keyFields.join(", ") + "] ——");
  if (rows.length === 0) {
    print("✅ 无重复，共 0 组。(limit=" + limit + ")");
  } else {
    print("⛔ 发现 " + rows.length + " 组重复 (最多展示 " + limit + " 组，按 count 倒序)：");
    rows.forEach(function (r, i) {
      print("  [" + (i + 1) + "] key=" + tojson(r.key) + "  count=" + r.count);
      if (showSamples) print("       sampleIds=" + tojson(r.sampleIds));
    });
    print("\n👉 取某一组明细示例（复制改 key 值）：");
    if (isSingle) {
      print('   db.getCollection("' + collName + '").find({ "' + keyFields[0] + '": ' + tojson(rows[0].key) + ' }).limit(10);');
    } else {
      var cond = {};
      keyFields.forEach(function (f) { cond[f] = "<把上面 key 里对应字段的值粘过来>"; });
      print("   // 复合键示例：db.getCollection(\"" + collName + "\").find(" + tojson(cond) + ").limit(10);");
      print("   // 第一组 key = " + tojson(rows[0].key));
    }
  }
  return rows;
}

/* ---------------- 本项目预设：sooncode.domain 核心唯一索引 ---------------- */

function checkSooncodeCore(targetDb) {
  // 注意：字段名与 MongoDocumentMapper 对齐：id / streamId / version
  var database = targetDb || db;
  return checkSooncodeCoreOnDb(database);
}

function checkSooncodeCoreOnDb(database) {
  var checks = [
    { coll: "eventMetadata", keys: ["id"] },
    { coll: "eventSource", keys: ["streamId", "version"] },
    { coll: "eventSnapshot", keys: ["streamId"] }
  ];
  var summary = {};
  checks.forEach(function (c) {
    // 临时切换 db 上下文：用 database.getCollection 跑聚合，避免 use() 跳库
    summary[c.coll] = findOnDb(database, c.coll, c.keys, { limit: 5 });
  });
  print("\n========== 汇总 ==========");
  var hasDup = false;
  Object.keys(summary).forEach(function (k) {
    var n = summary[k].length;
    if (n > 0) hasDup = true;
    print((n === 0 ? "✅ " : "⛔ ") + k + "：重复 " + n + " 组");
  });
  if (hasDup) {
    print("\n⚠️ 存在重复！此时 Java 端 MongoIndexInitializer.ensureIndex 会拒绝建唯一索引并抛 DomainException。");
    print("   请先清洗/合并重复数据，再重启应用让索引重建。");
  } else {
    print("\n✅ 核心集合均无重复，可以安全建唯一索引。");
  }
  return summary;
}

// 在指定 database 对象上跑判重（不依赖全局 db，方便 checkDb 循环调用）
function findOnDb(database, collName, keyFields, opts) {
  opts = opts || {};
  var limit = opts.limit != null ? opts.limit : 20;
  var sampleSize = opts.sampleSize != null ? opts.sampleSize : 5;
  if (typeof keyFields === "string") keyFields = [keyFields];

  var coll = database.getCollection(collName);
  var groupId = {};
  keyFields.forEach(function (f) { groupId[f] = "$" + f; });
  var isSingle = keyFields.length === 1;

  var pipeline = [
    { $group: { _id: isSingle ? ("$" + keyFields[0]) : groupId, count: { $sum: 1 }, sampleIds: { $push: "$_id" } } },
    { $match: { count: { $gt: 1 } } },
    { $sort: { count: -1 } },
    { $limit: limit }
  ];

  var rows = [];
  try {
    rows = coll.aggregate(pipeline, { allowDiskUse: true }).toArray();
  } catch (e) {
    print("❌ [" + collName + "] 查询失败（集合可能不存在）：" + e.message);
    return [];
  }
  print("—— [" + database.getName() + "." + collName + "] 判重字段: [" + keyFields.join(", ") + "] ——");
  if (rows.length === 0) {
    print("✅ 无重复。");
  } else {
    print("⛔ 发现 " + rows.length + " 组重复：");
    rows.forEach(function (r, i) {
      print("  [" + (i + 1) + "] key=" + tojson(r._id) + "  count=" + r.count + "  sampleIds=" + tojson(r.sampleIds.slice(0, sampleSize)));
    });
  }
  return rows;
}

/* ---------------- 自动扫描：按已有 unique 索引逐个判重 ---------------- */

/**
 * 扫描某集合上所有 unique 索引，逐个查重复（建唯一索引前必跑）
 * @param {string} collName
 * @param {object} [opts] { limit }
 */
function checkUniqueIndexes(collName, opts) {
  opts = opts || {};
  var limit = opts.limit != null ? opts.limit : 10;
  print("========== 扫描集合唯一索引 [" + db.getName() + "." + collName + "] ==========");
  var indexes;
  try {
    indexes = db.getCollection(collName).getIndexes();
  } catch (e) {
    print("❌ 获取索引失败：" + e);
    return {};
  }
  var uniques = indexes.filter(function (ix) { return ix.unique === true; });
  // _id 天然唯一，跳过（除非你怀疑分片/导入异常，传 opts.includeId=true 可强制查）
  if (!opts.includeId) uniques = uniques.filter(function (ix) { return ix.name !== "_id_"; });

  if (uniques.length === 0) {
    print("ℹ️ 该集合没有自定义 unique 索引（仅 _id_）。");
    return {};
  }
  print("发现 " + uniques.length + " 个 unique 索引，逐个判重…");
  var result = {};
  uniques.forEach(function (ix) {
    var fields = Object.keys(ix.key).filter(function (k) { return k !== "_id"; });
    // 跳过 _id 单独索引；含 _id 的复合索引一般也不会有重复，仍可查其余字段
    if (fields.length === 0) return;
    print("\n--- 索引 " + ix.name + "  key=" + tojson(ix.key) + " ---");
    result[ix.name] = findDuplicateKeys(collName, fields, { limit: limit });
  });
  return result;
}

/**
 * 扫描当前库所有集合的 unique 索引并逐个判重
 * @param {object} [opts] { limit, excludePrefixes }
 */
function checkDb(opts) {
  opts = opts || {};
  var names = db.getCollectionNames().filter(function (n) { return !n.startsWith("system."); });
  print("========== 全库唯一索引重复扫描 [" + db.getName() + "]，共 " + names.length + " 个集合 ==========");
  var summary = {};
  names.forEach(function (n) { summary[n] = checkUniqueIndexes(n, opts); });
  print("\n========== 全库汇总 ==========");
  Object.keys(summary).forEach(function (coll) {
    Object.keys(summary[coll]).forEach(function (ixName) {
      var n = summary[coll][ixName].length;
      print((n === 0 ? "✅ " : "⛔ ") + coll + " / " + ixName + "：重复 " + n + " 组");
    });
  });
  return summary;
}

/* ---------------- 命令行 --eval 传参自动执行 ---------------- */
// 允许：--eval 'var TARGET_COLLECTION="user"; var TARGET_KEYS=["email"]'
//       --eval 'var CHECK_CORE=true'           只查本项目核心 3 表
//       --eval 'var CHECK_ALL=true'            全库扫描
//       --eval 'var TARGET_DB="myDatabase"'    先切库（配合 --file 用）
(function autoRun() {
  try {
    if (typeof TARGET_DB !== "undefined" && TARGET_DB) {
      // mongosh 切库
      db = db.getSiblingDB(TARGET_DB);
      print("📦 已切换数据库：" + db.getName());
    }
    if (typeof TARGET_COLLECTION !== "undefined" && TARGET_COLLECTION) {
      var keys = (typeof TARGET_KEYS !== "undefined" && TARGET_KEYS) ? TARGET_KEYS : ["id"];
      var lim = (typeof TARGET_LIMIT !== "undefined" && TARGET_LIMIT) ? TARGET_LIMIT : 20;
      findDuplicateKeys(TARGET_COLLECTION, keys, { limit: lim });
      return;
    }
    if (typeof CHECK_ALL !== "undefined" && CHECK_ALL) {
      checkDb({});
      return;
    }
    if (typeof CHECK_CORE !== "undefined" && CHECK_CORE) {
      checkSooncodeCoreOnDb(db);
      return;
    }
    // --file 直接执行、无传参时：默认跑本项目核心检查（最常用），避免“跑完没输出”的困惑
    if (typeof TARGET_COLLECTION === "undefined" && typeof CHECK_ALL === "undefined" && typeof CHECK_CORE === "undefined") {
      // 仅当通过 --file 执行时才自动跑；load() 引入时不打扰（用调用栈特征区分不可靠，故只在非交互难以区分时也跑，
      // 但 load 后重复跑一次也无害）。如只想引入函数不想自动跑，执行前先定义 var NO_AUTO_RUN=true
      if (typeof NO_AUTO_RUN !== "undefined" && NO_AUTO_RUN) return;
      print("ℹ️ 未指定 TARGET_COLLECTION，默认执行本项目核心 3 表检查（eventMetadata/eventSource/eventSnapshot）。");
      print("   如需查业务表，改用 --eval 传参，见文件头用法 B。\n");
      checkSooncodeCoreOnDb(db);
    }
  } catch (e) {
    print("自动执行跳过：" + e);
  }
})();

