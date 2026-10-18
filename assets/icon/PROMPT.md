# 图标处理

使用内置 image_gen 编辑用户提供的 reference.png。原图与生成结果分别保留，generate-icons.py 只做平台尺寸和格式转换。

保留机器人与半齿轮的轮廓、比例、银蓝色及深蓝背景，去除细密金属划痕与背景颗粒，改为平滑的大面积渐变，使小尺寸图标更清楚。

Android 自适应图标的前景在 ic_launcher_foreground.xml 中使用四边 20% 留白。相对原先 10% 留白，图案缩小至原来的 75%，避免被启动器的圆形遮罩挤满或裁切。原始图案与应用内图片继续保留完整分辨率。

生成提示词：

```text
Use case: precise-object-edit. Asset type: production application icon for Set-for-U, Android launcher and Windows/Linux portable app. Edit target: the supplied square image. Preserve the exact central symbol silhouette: an Android-like robot body, two rounded detached arms, two rounded legs, and a cog/gear upper half head with the same proportions and symmetric shape, with no eyes or antennae added. Preserve the icy silver-blue palette and dark navy background. Remove ALL fine metal grain, horizontal scratches, fabric-like surface textures, background noise, and fine ripples. Replace them with clean smooth broad silver-to-ice-blue gradients, restrained cyan edge highlights, and a uniformly clean dark navy background. Simplify the thin interior arc and edges so the mark remains crisp and recognizable at 24 to 64 pixels. Do not introduce tiny details. Keep broad gaps between detached parts, keep subject centered, leave a consistent 10 percent outer safe margin. No text, no watermark, no new objects. Output one square icon asset, not a presentation or mockup.
```
