package com.kaisar.xposed.godmode.engine.store;

import android.graphics.Bitmap;

import com.kaisar.xposed.godmode.engine.rule.ViewRule;

import java.io.IOException;
import java.util.Map;

/**
 * 规则存储接口 — 定义规则持久化的标准协议。
 * 实现类负责 JSON 序列化、文件原子写入、图片存储等细节。
 */
public interface IRuleStore {

    /** 加载所有应用的规则 */
    Map<String, Map<String, java.util.List<ViewRule>>> loadAll() throws IOException;

    /** 加载指定包的规则 */
    Map<String, java.util.List<ViewRule>> load(String packageName) throws IOException;

    /** 保存指定包的规则（原子写入） */
    void save(String packageName, Map<String, java.util.List<ViewRule>> rules)
            throws IOException;

    /** 保存 Bitmap 图片并返回文件路径 */
    String saveBitmap(String packageName, Bitmap bitmap) throws IOException;

    /** 删除指定包的所有规则文件 */
    void delete(String packageName) throws IOException;

    /** 删除指定包中的单条规则 */
    void delete(String packageName, ViewRule rule) throws IOException;

    /** 清理未被任何规则引用的孤立图片文件 */
    void cleanOrphanImages() throws IOException;
}
