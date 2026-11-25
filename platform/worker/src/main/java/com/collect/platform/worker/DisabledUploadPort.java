package com.collect.platform.worker;

import org.springframework.stereotype.Component;

@Component
public class DisabledUploadPort implements UploadPort {
    @Override
    public String status() {
        return "disabled: 上传适配器未实现（X01 契约与 G04 传输安全三项未解除）";
    }
}
