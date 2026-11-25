package com.collect.platform.worker;

/**
 * 上传适配器端口。X01/G04 未解除前不得实现任何真实发送。
 */
public interface UploadPort {
    String status();
}
