package ticode.zh.android;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.text.*;
import java.nio.channels.*;
import java.util.zip.CRC32;
import java.security.*;
import android.content.Intent;
import android.net.Uri;
import java.io.*;
import java.util.*;
import android.net.Uri;
import android.content.*;
import android.provider.*;
import android.graphics.*;
import java.util.*;
import java.text.*;

import ticode.zh.jvm.文件;
import ticode.zh.jvm.集合;

public class 文件操作 {

public static boolean 重命名文件(String 原路径, String 新路径) {
if (新路径.equals(原路径)) {
return true;
}
File oldfile = new File(原路径);
if (!oldfile.exists()) {
return false;
}
File newfile = new File(新路径);
if (newfile.exists()) {
return false;
}
if (oldfile.renameTo(newfile)) {
return true;
}
return false;
}

public static boolean 复制文件(String 文件路径, String 欲复制到路径) {
try {
copyTo(new File(文件路径), new File(欲复制到路径));
} catch (IOException e) {
e.printStackTrace();
return false;
}
return true;
}

public static void 移动文件(String 文件路径, String 欲移到路径) {
try {
moveTo(new File(文件路径), new File(欲移到路径));
} catch (IOException e) {
e.printStackTrace();
}
}

// 获取文件后缀名的方法
public static String 取文件后缀名(String 文件名) {
int lastIndex = 文件名.lastIndexOf(".");
if (lastIndex != -1) {
return 文件名.substring(lastIndex + 1);
} else {
return "";
}
}

// 获取文件前缀名的方法
public static String 取文件前缀名(String 文件名) {
int lastIndex = 文件名.lastIndexOf(".");
if (lastIndex != -1) {
return 文件名.substring(0, lastIndex);
} else {
return 文件名;
}
}

public static String 取文件名(String 路径) {
文件 目标文件 = 路径;
return (目标文件.取文件名());
}

public static String 取文件MD5(String 路径) {
try {
return getMD5(new File(路径));
} catch (IOException e) {
e.printStackTrace();
}
return "";
}

public static String 取文件SHA1(String 路径) {
try {
return getSHA1(new File(路径));
} catch (IOException e) {
e.printStackTrace();
}
return "";
}

public static String 取文件CRC32(String 路径) {
try {
byte[] buffer = new byte[8192];
CRC32 crc = new CRC32();
FileInputStream fis = new FileInputStream(路径);
while (true) {
int r = fis.read(buffer);
if (r == -1) {
break;
}
crc.update(buffer, 0, r);
}
fis.close();
return Long.toHexString(crc.getValue());
} catch (IOException e) {
e.printStackTrace();
}
return "";
}

public static String 取文件哈希值(String 文件路径) {
try {
MessageDigest digest = MessageDigest.getInstance("SHA-256");
FileInputStream fis = new FileInputStream(文件路径);
byte[] buffer = new byte[8192];
int count;
while ((count = fis.read(buffer)) > 0) {
digest.update(buffer, 0, count);
}

byte[] hashBytes = digest.digest();
StringBuilder hexString = new StringBuilder();
for (byte b : hashBytes) {
hexString.append(String.format("%02x", b));
}
return hexString.toString();
} catch (Exception e) {
e.printStackTrace();
}
return "";
}

public static void 追加文件内容(String 路径, String 欲追加文本) {
try {
append(new File(路径), 欲追加文本);
} catch (IOException e) {
e.printStackTrace();
}
}

public static boolean 删除文件(String 路径) {
return deleteFile(new File(路径));
}

public static boolean 创建目录(String 路径) {
return createDirectory(new File(路径));
}

public static boolean 创建文件(String 路径) {
return createFile(new File(路径));
}

public static boolean 是否为目录(String 路径) {
File file = new File(路径);
if (file.exists() && file.isDirectory()) {
return true;
}
return false;
}

public static boolean 是否为隐藏文件(String 路径) {
File file = new File(路径);
if (file.exists()) {
return file.isHidden();
}
return false;
}

public static boolean 文件是否存在(String 路径) {
return new File(路径).exists();
}

public static String 取文件编码(String 路径) {
try {
BufferedInputStream in = new BufferedInputStream(new FileInputStream(new File(路径)));
in.mark(4);
byte[] first3bytes = new byte[3];
in.read(first3bytes);
in.reset();
if (first3bytes[0] == (byte) -17 && first3bytes[1] == (byte) -69 && first3bytes[2] == (byte) -65) {
return "utf-8";
}
if (first3bytes[0] == (byte) -1 && first3bytes[1] == (byte) -2) {
return "unicode";
}
if (first3bytes[0] == (byte) -2 && first3bytes[1] == (byte) -1) {
return "utf-16be";
}
if (first3bytes[0] == (byte) -1 && first3bytes[1] == (byte) -1) {
return "utf-16le";
}
return "GBK";
} catch (Exception e) {
//e.printStackTrace();
throw new RuntimeException("取文件编码( 未找到文件:" + 路径);
}
}

public static String 读入文本文件(String 路径, String 编码) {
try {
BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(路径), 编码));
boolean first = true;
StringBuilder content = new StringBuilder();
String line;
while ((line = br.readLine()) != null) {
if (first) {
first = false;
content.append(line);
} else {
content.append('\n').append(line);
}
}
br.close();
return content.toString();
} catch (IOException e) {
e.printStackTrace();
}
return "";
}

public static void 写出文本文件(String 路径, String 欲写出内容) {
try {
write(new File(路径), 欲写出内容);
} catch (IOException e) {
e.printStackTrace();
}
}

public static byte[] 读入文件字节(String 路径) {
byte[] buffer = null;
if (!new File(路径).exists()) {
return null;
}
try {
FileInputStream fin = new FileInputStream(路径);
buffer = new byte[fin.available()];
fin.read(buffer);
fin.close();
return buffer;
} catch (Exception e) {
e.printStackTrace();
throw new RuntimeException("读入字节文件( 错误");
}
}

public static boolean 写出字节文件(String 路径, byte[] 欲写出字节集) {
try {
FileOutputStream fout = new FileOutputStream(路径);
fout.write(欲写出字节集);
fout.close();
return true;
} catch (Exception e) {
e.printStackTrace();
throw new RuntimeException("写出字节文件( 错误");
}
}

//取文件大小，仅获取单个文件大小
public static long 取文件大小(String 路径) {
return new File(路径).length();
}

//取文件大小，如果是文件夹，会获取文件夹下所有文件的大小
public static long 取文件大小2(String 路径) {
File file = new File(路径);
try {
if (file.isDirectory()) {
return getFileSizes(file);
}
return getFileSize(file);
} catch (Exception e) {
e.printStackTrace();
throw new RuntimeException("取文件大小 错误" + e);
}
}

//将取得的文件大小转换为带单位的大小数据,仅获取单个文件大小
public static String 转换文件大小(String 路径, int 保留位数) {
long size = 取文件大小(路径);
return convertFileSize(size,保留位数);
}

//将取得的文件大小转换为带单位的大小数据,本方法在是文件夹的情况下会获取文件夹下所有文件的大小
public static String 转换文件大小2(String 路径, int 保留位数) {
long size = 取文件大小2(路径);
return convertFileSize(size,保留位数);
}

public static boolean 写出资源文件(安卓环境 窗口环境, String 文件名称, String 欲写到路径) {
try {
InputStream stream = 窗口环境.getAssets().open(文件名称);
File file = new File(欲写到路径);
if (!file.getParentFile().exists()) {
file.getParentFile().mkdirs();
}
if (stream != null && writeStreamToFile(stream, file)) {
return true;
}
return false;
} catch (Exception e) {
e.printStackTrace();
throw new RuntimeException("写出资源文件( "  + 文件名称 + "或" + 欲写到路径);
}
}

public static String 读入资源文件(安卓环境 窗口环境, String 文件名称, String 编码) {
try {
InputStream inputstream = 窗口环境.getAssets().open(文件名称);
if (inputstream == null) {
return "";
}
int length = inputstream.available();
byte[] buffer = new byte[length];
inputstream.read(buffer);
String res2 = new String(buffer, 0, length, 编码);
inputstream.close();
return res2;
} catch (IOException e) {
e.printStackTrace();
throw new RuntimeException("读入资源文件( 未找到文件: "  + 文件名称);
}
}

public static String 寻找文件关键词(String 路径, String 关键词) {
String result = "";
for (File f : new File(路径).listFiles()) {
if (f.getName().indexOf(关键词) >= 0) {
result = f.getPath() + "\n" + result;
}
}
return result;
}

public static String 寻找文件后缀名(String 路径, String 后缀名) {
String result = "";
for (File f : new File(路径).listFiles()) {
if (f.getPath().substring(f.getPath().length() - 后缀名.length()).equals(后缀名) && !f.isDirectory()) {
result = f.getPath() + "\n" + result;
}
}
return result;
}

public static boolean 打开文本文件_读(String 文件路径, String 编码) {
if (!new File(文件路径).exists()) {
return false;
}
try {
fin = new FileInputStream(文件路径);
isr = new InputStreamReader(fin, 编码);
br = new BufferedReader(isr);
return true;
} catch (Exception e) {
e.printStackTrace();
return false;
}
}

public static boolean 关闭读() {
try {
br.close();
fin.close();
return true;
} catch (Exception e) {
e.printStackTrace();
return false;
}
}

public static String 读一行() {
try {
String readLine = br.readLine();
line = readLine;
return line;
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public static boolean 打开文本文件_写(String 文件路径, String 编码) {
if (!new File(文件路径).exists()) {
return false;
}
try {
fout = new FileOutputStream(文件路径);
osw = new OutputStreamWriter(fout, 编码);
bw = new BufferedWriter(osw);
return true;
} catch (Exception e) {
e.printStackTrace();
return false;
}
}

public static boolean 关闭写() {
try {
bw.close();
fout.close();
return true;
} catch (Exception e) {
e.printStackTrace();
return false;
}
}

public static boolean 写一行(String 欲写内容) {
try {
bw.newLine();
bw.write(欲写内容);
bw.flush();
return true;
} catch (Exception e) {
e.printStackTrace();
return false;
}
}

public static String[] 取子目录(String 路径) {
File[] ff = new File(路径).listFiles();
String[] paths = new String[ff.length];
for (int i = 0; i < ff.length; i++) {
if (ff[i].isDirectory()) {
paths[i] = ff[i].getAbsolutePath();
}
}
return paths;
}





public static void 取所有文件路径(String 目标路径, java.util.List<String> 输出结果) {
文件 目标 = 文件.从路径创建(目标路径);
if (目标.为文件夹()) {
Object 子文件数组 = 目标.取子文件数组();
if (子文件数组 != null) {
while (子文件数组 -> 子文件) {
取所有文件路径(子文件.取绝对路径(),输出结果);
}
}
} else {
输出结果.添加成员(目标.取绝对路径());
}
}

public static final int 文件排序_时间排序 = 0;
public static final int 文件排序_名称排序 = 1;
public static final int 文件排序_名称排序_忽略大小写 = 2;
public static final int 文件排序_名称排序_本地化优先 = 3;
public static final int 文件排序_大小排序 = 4;

public static java.util.List<String> 取子文件集合(String 路径) {
ArrayList<String> list = new ArrayList<>();
File[] ff = new File(路径).listFiles();
for (int i = 0; i < ff.length; i++) {
list.add(ff[i].getAbsolutePath());
}
return list;
}

public static java.util.List<String> 取子文件集合2(String 路径, int 排序方式, boolean 是否正序) {
ArrayList<String> list = new ArrayList<>();
File[] fs = new File(路径).listFiles();
if (fs == null) {
return list; // 不执行
}
Arrays.sort(fs, new Comparator<File>() {
@Override
public int compare(File f1, File f2) {
boolean isDir1 = f1.isDirectory();
boolean isDir2 = f2.isDirectory();
if (isDir1 && !isDir2) {
return -1; // f1是目录，优先
} else if (!isDir1 && isDir2) {
return 1; // f2是目录，f2优先
}
int result = 0;
switch (排序方式) {
case 文件排序_名称排序: // 按名称排序
result = f1.getName().compareTo(f2.getName());
break;
case 文件排序_名称排序_忽略大小写: // 按名称忽略大小写，Aa-Zz规则排序，隐藏文件在最前，中文文件夹在最后
result = f1.getName().compareToIgnoreCase(f2.getName());
break;
case 文件排序_名称排序_本地化优先: // 按名称排序(本地化优先)
Collator collator = Collator.getInstance(Locale.getDefault());
result = collator.compare(f1.getName(), f2.getName());
break;
case 文件排序_时间排序: // 按时间排序
result = Long.compare(f1.lastModified(), f2.lastModified());
break;
case 文件排序_大小排序: // 按大小排序
result = Long.compare(f1.length(), f2.length());
break;
}
return 是否正序 ? -result : result;
}
});
for (File file : fs) {
list.add(file.getAbsolutePath());
}
return list;
}

public static String[] 取子文件列表(String 路径) {
List<String> list = new ArrayList<>();
File[] ff = new File(路径).listFiles();
for (int i = 0; i < ff.length; i++) {
list.add(ff[i].getAbsolutePath());
}
return list.toArray(new String[list.size()]);
}

public static String[] 取子文件列表2(String 路径, int 排序方式, boolean 是否正序) {
return 取子文件集合2(路径,排序方式,是否正序).到数组();
}

public static String 取文件修改时间(String 路径) {
return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(new File(路径).lastModified()));
}

private static FileInputStream fin;
private static InputStreamReader isr;
private static BufferedReader br;
private static String line;
private static FileOutputStream fout;
private static OutputStreamWriter osw;
private static BufferedWriter bw;





public static void moveTo(File from, File to) throws IOException {
if (from.isDirectory()) {
moveDir(from, to);
} else {
moveFile(from, to);
}
}

public static void copyTo(File from, File to) throws IOException {
if (from.isDirectory()) {
copyDir(from, to);
} else {
copyFile(from, to);
}
}

private static void moveDir(File oldFile, File newFile) throws IOException {
copyDir(oldFile, newFile);
deleteFile(oldFile);
}

private static void moveFile(File oldFile, File newFile) throws IOException {
copyFile(oldFile, newFile);
deleteFile(oldFile);
}

private static void copyDir(File srcFile, File dstFile) throws IOException {
if (!dstFile.exists())
dstFile.mkdirs();
for (File file : dstFile.listFiles()) {
if (file.isDirectory()) {
copyDir(file, new File(dstFile.getAbsolutePath() + File.separator + file.getName()));
} else {
copyFile(file, new File(dstFile.getAbsolutePath() + File.separator + file.getName()));
}
}
}

private static void copyFile(File srcFile, File dstFile) throws IOException {
if (!srcFile.exists()) {
return;
}
if (!dstFile.exists()) {
dstFile.getParentFile().mkdirs();
if (srcFile.isFile())
dstFile.createNewFile();
}
FileInputStream fileIns = null;
FileOutputStream fileOuts = null;
FileChannel source = null;
FileChannel destination = null;
try {
fileIns = new FileInputStream(srcFile);
fileOuts = new FileOutputStream(dstFile);
source = fileIns.getChannel();
destination = fileOuts.getChannel();
destination.transferFrom(source, 0, source.size());
} catch (IOException e) {
throw e;
} finally {
if (fileIns != null)
fileIns.close();
if (fileOuts != null)
fileOuts.close();
if (source != null)
source.close();
if (destination != null)
destination.close();
}
}



public static String getSHA1(File file) throws IOException {
return getDigest(file, "SHA1");
}

public static String getMD5(File file) throws IOException {
return getDigest(file, "MD5");
}

private static String getDigest(File file, String algo) throws IOException {
try {
MessageDigest md = MessageDigest.getInstance(algo);
byte[] buffer = new byte[8192];
FileInputStream fis = new FileInputStream(file);
while (true) {
int r = fis.read(buffer);
if (r == -1) {
break;
}
md.update(buffer, 0, r);
}
fis.close();
return new java.math.BigInteger(1, md.digest()).toString(16);
} catch (Exception e) {

}
return null;
}





public static void write(File file, String content) throws IOException {
if (!file.exists()) {
createFile(file);
}
BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
writer.write(content);
writer.flush();
writer.close();
}

public static void write(File file, byte[] bytes) throws IOException {
write(file, bytes, 0, bytes.length);
}

public static void write(File file, byte[] bytes, int offset, int len) throws IOException {
if (!file.exists()) {
createFile(file);
}
FileOutputStream fos = new FileOutputStream(file);
fos.write(bytes, offset, len);
fos.flush();
fos.close();
}





public static void append(File file, byte[] appendix) throws IOException {
append(file, appendix, 0, appendix.length);
}

public static void append(File file, byte[] appendix, int off, int len) throws IOException {
if (!file.exists()) {
createFile(file);
}
RandomAccessFile raf = new RandomAccessFile(file, "rw");
raf.seek(raf.length());
raf.write(appendix, off, len);
raf.close();
}

public static void append(File file, String appendix) throws IOException {
if (!file.exists()) {
createFile(file);
}
FileWriter fw = new FileWriter(file, true);
fw.write(appendix);
fw.flush();
fw.close();
}











public static List<File> searchByName(File file, String match, boolean useRegex, boolean ignoreCase,
boolean searchSub) {
if (!file.isDirectory()) {
throw new IllegalArgumentException("not a directory");
}
ArrayList<File> result = new ArrayList<>();
Pattern pattern = null;
if (useRegex) {
if (ignoreCase) {
pattern = Pattern.compile(match, Pattern.CASE_INSENSITIVE);
} else {
pattern = Pattern.compile(match);
}
} else {
if (ignoreCase) {
match = match.toLowerCase();
}
}
for (File f : file.listFiles()) {
searchByNameInternal(result, f, match, ignoreCase, useRegex, pattern, searchSub);
}
return result;
}




private static void searchByNameInternal(List<File> result, File file, String match, boolean ignoreCase,
boolean useRegex, Pattern pattern, boolean searchSub) {
if (useRegex) {
Matcher m = pattern.matcher(file.getName());
if (m.find()) {
result.add(file);
}
} else {
if (ignoreCase) {
if (file.getName().toLowerCase().contains(match)) {
result.add(file);
}
} else {
if (file.getName().contains(match)) {
result.add(file);
}
}
}
if (file.isDirectory() && searchSub) {
for (File sub : file.listFiles()) {
searchByNameInternal(result, sub, match, ignoreCase, useRegex, pattern, searchSub);
}
}
}






public static boolean deleteFile(File file) {
boolean success = true;
if (file.exists()) {
if (file.isDirectory()) {
for (File subFile : file.listFiles()) {
if (!success) {
return false;
}
success = success && deleteFile(subFile);
}
}
if (success)
success = success && file.delete();
}
return success;
}

public static boolean createFile(File file) {
try {
if (!file.getParentFile().exists()) {
file.getParentFile().mkdirs();
}
return file.createNewFile();
} catch (IOException e) {
e.printStackTrace();
return false;
}
}






public static boolean createDirectory(File file) {
return file.mkdirs();
}






public static String getFileName(String path) {
int pos = path.lastIndexOf(File.pathSeparator);
if (pos == -1) {
return path;
} else {
return path.substring(pos + 1, path.length());
}
}






public static String getFilePath(String path) {
int pos = path.lastIndexOf(File.pathSeparator);
if (pos == -1) {
return path;
} else {
return path.substring(0, pos + 1);
}
}

private static long getFileSize(File file) throws Exception {
return file.length();
}

private static long getFileSizes(File f) throws Exception {
long size = 0;
File[] flist = f.listFiles();
for (int i = 0; i < flist.length; i++) {
if (flist[i].isDirectory()) {
size += getFileSizes(flist[i]);
} else {
size += getFileSize(flist[i]);
}
}
return size;
}

private static boolean writeStreamToFile(InputStream stream, File file) throws FileNotFoundException, IOException {
OutputStream output = new FileOutputStream(file);
byte[] buffer = new byte[1024];
while (true) {
int read = stream.read(buffer);
if (read != -1) {
output.write(buffer, 0, read);
} else {
output.flush();
output.close();
stream.close();
return true;
}
}
}

public static String convertFileSize(long size,int bits) {
String bit = "";
for (int i = 0;i < bits;i++) {
bit += "#";
}
if (size <= 0) {
return "0 B";
}
final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
int digitGroups = (int) (Math.log10(size) / Math.log10(1024));

return new DecimalFormat("#,##0."+bit).format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
}

}