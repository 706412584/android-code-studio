package ticode.zh.android;

import android.graphics.Path;
import android.graphics.RectF;

import ticode.zh.base.对象;

public class 构建路径 extends android.graphics.Path {
// 具体化后必须显式调 super()：父类（Path/Paint/…）有 native 字段，
// 不调则实例未初始化，一用就 SIGSEGV。
public 构建路径() { super(); }
public static 构建路径 创建路径() {
return new 构建路径();
}








public void 填充模式(int 模式) {
switch (模式) {
case 0:
this.setFillType(Path.FillType.WINDING);
break;
case 1:
this.setFillType(Path.FillType.EVEN_ODD);
break;
case 2:
this.setFillType(Path.FillType.INVERSE_EVEN_ODD);
break;
case 3:
this.setFillType(Path.FillType.INVERSE_WINDING);
break;
default:
this.setFillType(Path.FillType.WINDING);
}
}

// 返回的是 Path.FillType 对象
public Object 填充模式() {
return this.getFillType();
}

public void 起点开始(float x, float y) {
this.moveTo(x,y);
}

public void 连接下一个点(float x, float y) {
this.lineTo(x,y);
}

public void 正圆(float x, float y, float 半径, boolean 顺时针) {
this.addCircle(x,y,半径, 顺时针 ? Path.Direction.CW : Path.Direction.CCW);
}

public void 椭圆(float x, float y, float 宽度, float 高度, boolean 顺时针) {
this.addOval(new RectF(x,y,宽度,高度), 顺时针 ? Path.Direction.CW : Path.Direction.CCW);
}

public void 正角矩形(float 起点x, float 起点y, float 终点x, float 终点y, boolean 顺时针) {
this.addRect(起点x,起点y,终点x,终点y, 顺时针 ? Path.Direction.CW : Path.Direction.CCW);
}

public void 圆角矩形(float 起点x, float 起点y, float 终点x, float 终点y, int 上圆角, int 下圆角, boolean 顺时针) {
this.addRoundRect(起点x,起点y,终点x,终点y,上圆角,下圆角, 顺时针 ? Path.Direction.CW : Path.Direction.CCW);
}

// 重点，坐标组例: 坐标组 = {x1,y1, x2,y2}，需要对应
public void 添加多边形(float[] 坐标组, int 起点偏移量, int 顶点数量, boolean 是否闭合) {
// android.graphics.Path 无 addPolygon（结绳调的是原生 Path 私有 API）。
// 按语义等价实现：把坐标组按 (x,y) 逐点连成多边形，可选闭合。
int 顶点数 = (顶点数量 > 0) ? 顶点数量 : ((坐标组.length - 起点偏移量) / 2);
if (顶点数 <= 0) {
return;
}
this.moveTo(坐标组[起点偏移量], 坐标组[起点偏移量 + 1]);
for (int i = 1; i < 顶点数; i++) {
this.lineTo(坐标组[起点偏移量 + i * 2], 坐标组[起点偏移量 + i * 2 + 1]);
}
if (是否闭合) {
this.close();
}
}

// 不依赖当前路径，独立添加弧形
public void 添加弧形(float 左, float 上, float 右, float 下, float 起始角度, float 扫过角度) {
this.addArc(左,上,右,下,起始角度,扫过角度);
}

// 链接当前终点开始绘制弧形
public void 连接弧形(float 左, float 上, float 右, float 下, float 起始角度, float 扫过角度, boolean 抬起) {
this.arcTo(左,上,右,下,起始角度,扫过角度,抬起);
}

public void 闭合区域() {
this.close();
}

// 清空路径，保留内部，(复用性高)
public void 清空路径() {
this.reset();
}

// 清除路径轮廓，保留填充模式等参数
public void 重置路径() {
this.rewind();
}

public boolean 是否为空() {
return this.isEmpty();
}

public void 替换路径(构建路径 路径) {
this.set(路径);
}

public void 平移路径(float x, float y) {
this.offset(x,y);
}

public boolean 是否包含坐标(float x, float y) {
// Path 无 contains；用 Region 做等价判定。
android.graphics.Region 区域 = new android.graphics.Region();
区域.setPath(this, 区域);
return 区域.contains((int) x, (int) y);
}

// 取两个区域共同包含的区域
public 构建路径 交集(构建路径 区域1, 构建路径 区域2) {
构建路径 resultPath = new 构建路径();
resultPath.set(区域1);
resultPath.op(区域2, Path.Op.INTERSECT);
return resultPath;
}

// 保留未被参数区域覆盖的区域
public boolean 差集(构建路径 区域) {
return this.op(区域, Path.Op.DIFFERENCE);
}

// 保留被参数区域覆盖的区域
public boolean 反向差集(构建路径 区域) {
return this.op(区域, Path.Op.REVERSE_DIFFERENCE);
}

// 保留非重叠，删除已重叠区域
public boolean 异或(构建路径 区域) {
return this.op(区域, Path.Op.XOR);
}

// 将第二个区域，合并进第一个区域
public boolean 合并(构建路径 原区域, 构建路径 新区域) {
return 原区域.op(新区域, Path.Op.UNION);
}

}
