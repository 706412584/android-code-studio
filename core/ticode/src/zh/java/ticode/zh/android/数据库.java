package ticode.zh.android;

import java.io.File;
import android.database.sqlite.SQLiteDatabase;

public class 数据库 {





public static android.database.sqlite.SQLiteDatabase 打开数据库(String 数据库路径) {
File file = new File(数据库路径);
File dirs = file.getParentFile();
if (!dirs.exists() && !dirs.mkdirs()) return null;
return SQLiteDatabase.openOrCreateDatabase(file, null);
}







public void 创建数据表(String 表名, String 结构) {}






public void 执行SQL语句(String SQL语句) {}






public android.database.Cursor 执行SQL查询语句(String SQL查询语句) {return null; }







public void 插入记录(String 表名, String 记录) {}







public void 删除记录(String 表名, String 条件) {}








public void 更新记录(String 表名, String 记录, String 条件) {}







public android.database.Cursor 查询记录(String 表名, String 条件) {return null; }




public void 关闭数据库() {}






public void 删除数据表(String 表名) {}





public static void 删除数据库(String 数据库路径) {
File file = new File(数据库路径);
if (!file.exists()) return;
SQLiteDatabase.deleteDatabase(file);
}
}