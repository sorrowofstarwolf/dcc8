# 第三轮优化算法说明

## 目标

第三轮优化的核心仍然是加快真正执行的 SM4 加密路径，同时修正与赛事方基准程序不一致的边界语义，保证输出文件可以被按字节校验通过。

本轮保持以下加密语义不变：

- 每个请求使用请求内的 `sm4Key`。
- 每个字段值独立执行 SM4-CBC 加密。
- IV 固定为 16 字节全 0。
- Padding 与标准 `SM4/CBC/PKCS5Padding` 兼容。
- 密文输出为大写 HEX。
- 掩码字段不执行 SM4，而是按基准程序的掩码规则输出。

## 相对第二轮的主要变化

### 1. 自研轻量 SM4 热路径

第二轮仍以减少重复加密为主，第三轮进一步减少每个明文字段的 SM4 本体开销。

当前实现不再在热路径中反复调用 JCE `Cipher.init/doFinal`，而是为每个请求创建一次 `Sm4Cipher.Context`：

- 校验当前请求的 16 字节 SM4 key。
- 展开 32 个轮密钥。
- 每个字段从固定 IV 开始独立执行 CBC。
- 密文直接写入请求级缓冲区，并原地转成大写 HEX。

这样避免了每个单元格重复进入 JCE provider 分发、对象状态初始化、通用 padding 流程和中间数组分配。

### 2. SM4 block 加密内联

SM4 block encrypt 被内联到项目内的 `Sm4Cipher.Context` 中，不再通过 BouncyCastle `SM4Engine.processBlock` 执行每个 16 字节块。

热路径变为：

```text
明文字节
  -> CBC XOR
  -> 组装 4 个 int
  -> 32 轮 SM4 block encrypt
  -> 写回 16 字节密文
  -> 转换为大写 HEX
```

这减少了 block 调用边界、临时 `workBlock` 数组和部分数组读写。

### 3. T 表折叠轮函数

SM4 每轮核心为 `SBOX -> L 线性变换 -> XOR`。当前实现预计算 4 张 256 项 T 表：

```text
T0 / T1 / T2 / T3
```

每轮通过 4 次查表和异或完成非线性与线性变换：

```text
T0[b0] ^ T1[b1] ^ T2[b2] ^ T3[b3]
```

这是第三轮 SM4 本体加速的主要来源。

### 4. 短字段专用路径

比赛数据中大量 SM4 字段为短字符串，例如 `user_id`、`user_code`、`secret_code`。当前实现为常见短长度增加单块专用路径，直接拼接 padding 后的 CBC 输入 word，减少通用路径中的分支和 padding 判断。

普通长度仍保留通用 CBC 路径：

- 小于 16 字节：单块通用路径。
- 小于 32 字节：双块路径。
- 更长字段：通用 CBC 多块路径。

### 5. 请求内缓存保留当前密钥语义

对低基数字段仍使用请求内密文缓存，但缓存只在当前请求内有效：

- 不跨请求共享密文。
- 不跨不同 `sm4Key` 共享密文。
- 缓存命中时直接把大写 HEX 密文写入输出缓冲区。
- 缓存未命中时走当前请求的 `Sm4Cipher.Context` 加密。

这样既保留了正确的密钥隔离，也减少了低基数字段的重复 SM4 计算。

## 最近校验修正

### 1. 请求字段数量

之前实现受赛事错误提示影响，限制了请求字段数量。当前实现改为允许请求中包含全部 11 个字段，只要字段名存在于支持列表中即可。

支持字段为：

```text
user_id, serial_no, user_code, business_key, id_card, phone,
name, email, device_id, trans_id, secret_code
```

其中掩码字段为：

```text
id_card, phone, name, email
```

其余字段按 SM4 加密输出。

### 2. CSV 加载解析

加载 CSV 时不再使用简单逗号切分，而是支持基础 CSV 引号语义：

- 字段可被双引号包裹。
- 引号字段内部允许逗号。
- `""` 会被解析为一个普通双引号。
- 行尾 `CRLF` 或 `LF` 均可读取。

解析后的字段字节进入列式存储，后续请求不再重复解析 CSV。

### 3. 掩码字段语义

掩码逻辑已改为与基准程序一致的 Java `String` 字符语义，而不是 UTF-8 字节语义：

```text
空字符串 -> 空输出
长度 <= 6 -> 首字符 + "#####"
长度 > 6  -> 首字符 + "####" + 末字符
```

这会影响中文姓名等多字节字符字段。例如 `何xx` 这类姓名字段应输出为 `何#####`，而不是按字节截断或保留错误尾字节。

### 4. 输出文件行终止符

生成 CSV 文件时，每一行都以 `LF` 结束，包括最后一行：

```text
row1\n
row2\n
...
last_row\n
```

当前输出不再写 `CRLF`，也不会省略最后一行末尾的 `LF`。这与最近对标准输出文件的字节级观察保持一致，有利于通过赛事方按字节校验。

## 当前处理流程

```text
收到加密请求
  -> 校验 requestId / sm4Key / fieldsToEncrypt
  -> 允许最多 11 个合法字段
  -> 获取加载期列式数据
  -> 为当前请求创建 Sm4Cipher.Context
  -> 对请求内可缓存字段构建当前 key 下的密文缓存
  -> 按行输出
      -> 掩码字段：写加载期预计算的掩码结果
      -> 缓存 SM4 字段：按 rowValueId 写请求内密文
      -> 普通 SM4 字段：执行轻量 SM4 热路径
      -> 每行末尾写 LF
  -> flush 输出文件
  -> 回调赛事方
```

## 正确性校验

当前本地校验覆盖：

```text
VERIFY_SM4_BC_OK lengths=0..64
VERIFY_FAST_OUTPUT_OK rows=10 bytes=820
VERIFY_ALL_FIELDS_BC_OK fields=11
VERIFY_QUOTED_CSV_BC_OK
```

其中：

- `VERIFY_SM4_BC_OK` 使用 BouncyCastle 标准 `SM4/CBC/PKCS5Padding` 对照 0 到 64 字节明文。
- `VERIFY_FAST_OUTPUT_OK` 覆盖典型请求字段组合和最终文件行终止符。
- `VERIFY_ALL_FIELDS_BC_OK` 覆盖全部 11 个字段。
- `VERIFY_QUOTED_CSV_BC_OK` 覆盖引号 CSV 字段和逗号字段解析。

固定样例仍保持：

```text
CZJE -> 91DFB6ABB4860D131618809DB26B7A5E
```

## 取舍

第三轮没有继续做 32 轮完全展开。T 表折叠后 SM4 本体已经明显加速，完全展开会显著增加代码体积和维护成本，预计收益只剩少量百分点。

当前优先级是：保持与基准程序完全一致的输出语义，在此前提下减少每块 SM4 加密的 CPU 成本。
