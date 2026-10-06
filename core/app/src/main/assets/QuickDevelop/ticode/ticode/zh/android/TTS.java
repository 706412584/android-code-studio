package ticode.zh.android;

import android.speech.tts.TextToSpeech;
import java.util.*;
import android.content.*;

import ticode.zh.jvm.语言环境;

public class TTS extends 窗口组件 {

private Context context;
private TextToSpeech mSpeech;

public TTS(Context context) {
super(context);
this.context = context;
mSpeech = new TextToSpeech(context, new TTSListener());
mSpeech.setLanguage(Locale.CHINESE);
}

private class TTSListener implements TextToSpeech.OnInitListener {
@Override
public void onInit(int status) {
if (status == TextToSpeech.SUCCESS) {
初始化完毕(true);
} else {
初始化完毕(false);
}
}
}

//设置朗读和转文件的语速
public void 语速(double 语速) {
mSpeech.setSpeechRate((float) 语速);
}

//设置朗读和转文件的语速，音调越高，越偏向女声
public void 音调(double 音调) {
mSpeech.setPitch((float) 音调);
}

//设置语言环境
public void 语言(java.util.Locale 语言) {
mSpeech.setLanguage(语言);
}

//获取TTS朗读状态，若正在朗读，则返回真，反之则假
public boolean 朗读状态() {
return mSpeech.isSpeaking();
}

//朗读一段文本
public void 朗读文本(String 欲朗读文本) {
mSpeech.speak(欲朗读文本, TextToSpeech.QUEUE_FLUSH, null);
}

//将文本转为wav语音文件
public void 文本转wav(String 欲转换文本, String 保存路径) {
HashMap<String, String> myHashRender = new HashMap<>();
myHashRender.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, 欲转换文本);
mSpeech.synthesizeToFile(欲转换文本, myHashRender, 保存路径);
}

//关闭TTS
public void 关闭() {
mSpeech.shutdown();
}

//停止朗读
public void 停止() {
mSpeech.stop();
}

//TTS初始化完毕触发该事件，返回是否初始化成功
public void 初始化完毕(boolean 结果) { } // 事件

}