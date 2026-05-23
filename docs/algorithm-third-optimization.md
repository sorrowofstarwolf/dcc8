# 第三轮优化算法说明

## 目标

第三轮优化不再扩大字典化范围，而是集中加快真正的 SM4 加密热路径。第二轮已经通过加载期字典和快速 CSV 写出减少了重复计算，本轮的重点是：当字段确实需要执行 SM4 时，降低每个明文块的加密成本。

本轮保持以下语义不变：

- 对外 REST 接口、请求字段和回调语义不变。
- 每个请求仍使用自己的 `sm4Key`。
- 每个字段值仍按 SM4-CBC、固定 IV、PKCS5/PKCS7 padding 独立加密。
- 输出仍为大写 HEX、UTF-8、CRLF、无表头、末尾无额外空行。
- 字典化字段只在请求内复用当前密钥下的密文，不跨请求共享密文。

## 相对第二轮的主要变化

### 1. 替换 JCE Cipher 调用链

第二轮基线中，单元格加密依赖 JCE `Cipher.init/doFinal`。该路径正确但每次字段值加密都会经过 provider 分发、对象状态初始化、padding 处理和通用 block 流程。

第三轮先将 SM4 实现改为项目内专用 CBC/PKCS7 流程：

- 每个请求创建一次 `Sm4Cipher.Context`。
- `Context` 内保存当前密钥展开后的 32 个轮密钥。
- 每个字段值从固定 IV 开始独立 CBC 加密。
- 密文字节直接写入目标 buffer，并原地倒序转换为大写 HEX。

这样避免了每个字段值重复初始化 JCE `Cipher` 的成本。

### 2. 内联 SM4 block encrypt

本轮将 SM4 的 key schedule、SBOX、轮函数和 block encrypt 内联到 `Sm4Cipher.Context`，不再通过 BouncyCastle `SM4Engine.processBlock` 执行每个 16 字节块。

热路径形式变为：

```text
明文字节
  -> CBC XOR 直接组装为 4 个 int
  -> 32 轮 SM4 block encrypt
  -> 写回 16 字节密文
  -> 原地转 HEX
```

这样减少了中间 `workBlock` 数组、block 调用边界和部分数组读写。

### 3. 使用 T 表折叠轮函数

SM4 每轮核心是：

```text
SBOX -> L 线性变换 -> XOR
```

直接实现时，每轮需要多次 SBOX 访问、rotate 和 xor。本轮新增 4 张 256 项 T 表：

```text
T0/T1/T2/T3
```

每轮将线性变换折叠为 4 次查表和异或：

```text
T0[b0] ^ T1[b1] ^ T2[b2] ^ T3[b3]
```

这一步是本轮最大的 SM4 本体收益来源。

### 4. 短字段专用路径

比赛数据中的 SM4 字段大量是短字段，例如：

- `user_code` 常见 4 字节
- `user_id` 常见 5 字节
- `secret_code` 常见 6 字节

本轮为 4、5、6 字节明文增加单块专用路径，直接拼接 padding 后的 CBC 输入 word，减少通用 `value()` 分支和 padding 判断。

对于其他长度，仍保留：

- `<16` 字节单块通用路径
- `<32` 字节双块路径
- 更长字段通用 CBC 路径

### 5. 字典密文直接写入最终数组

第二轮请求内构建字典密文时，先加密到 `tempBuffer`，再复制到请求级 `encryptedBytes`。第三轮改为：

```text
cipher.encryptToHex(..., encryptedBytes, position)
```

密文直接落到最终连续数组中，减少一次 `System.arraycopy`。

## 当前处理流程

```text
EncryptTask
  -> 创建当前请求的 Sm4Cipher.Context
      -> 校验 key 长度
      -> 展开 32 个 SM4 轮密钥
  -> 对请求中的字典字段加密唯一值
      -> 直接写入请求级 encryptedBytes
  -> 遍历每一行
      -> 掩码字段：写加载期预计算结果
      -> 字典 SM4 字段：按 rowValueId 写请求级密文
      -> 普通 SM4 字段：走当前 Context 的 SM4 热路径
  -> FastCsvWriter 批量写出
```

## 正确性校验

本轮新增了更强的本地校验方式：使用 BouncyCastle 标准 `SM4/CBC/PKCS5Padding` 作为对照，对 0 到 64 字节明文逐一比对当前实现输出。

已通过：

```text
VERIFY_SM4_BC_OK lengths=0..64
VERIFY_FAST_OUTPUT_OK rows=10 bytes=828
```

其中固定样例仍保持：

```text
CZJE -> 91DFB6ABB4860D131618809DB26B7A5E
```

## 取舍

本轮没有继续做 32 轮完全展开。原因是 T 表后 SM4 本体已经明显加速，完全展开会显著增加代码体积和维护成本，预计收益只剩几个百分点。当前实现选择在性能和可维护性之间保持平衡。

