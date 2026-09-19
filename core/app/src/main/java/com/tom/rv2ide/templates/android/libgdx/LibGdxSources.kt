/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.android.libgdx

/**
 * libGDX 模板的源码文本。
 *
 * 生成两个类：
 * - `MainActivity` — 继承 `AndroidApplication`，把渲染交给 `GameListener`
 * - `GameListener` — 最小可玩 demo：拖动挡板接住下落方块得分，漏掉即失败
 *
 * 所有 libGDX API 均按 1.14.2 实测签名书写（`javap` 核对过），未使用任何
 * 需要额外资源文件的能力：不加载贴图、不读外部字体，仅用 `ShapeRenderer`
 * 画形状、用 `BitmapFont()` 默认构造的内置字体画文字。
 */
object LibGdxSources {

  /** 生成的 Java 类所在的包内类名，供模板在需要时引用。 */
  const val LISTENER_CLASS = "GameListener"

  fun mainActivityKotlin(packageId: String): String =
      """
      package $packageId

      import android.os.Bundle
      import com.badlogic.gdx.backends.android.AndroidApplication
      import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration

      /**
       * libGDX 入口 Activity。
       *
       * 生命周期完全交给 [AndroidApplication] 处理——它在 onCreate 里创建
       * SurfaceView、建立 OpenGL 上下文并驱动渲染循环，我们只需要把
       * [GameListener] 交给它。
       */
      class MainActivity : AndroidApplication() {

          override fun onCreate(savedInstanceState: Bundle?) {
              super.onCreate(savedInstanceState)

              val config = AndroidApplicationConfiguration().apply {
                  // 只需要颜色缓冲，不需要深度/模板，省一点内存和带宽
                  depth = 0
                  stencil = 0
                  // 不使用传感器与音频，最小 demo 用不到
                  useAccelerometer = false
                  useGyroscope = false
                  useCompass = false
                  disableAudio = true
                  // 全屏沉浸，游戏里没有系统栏的位置
                  useImmersiveMode = true
              }

              initialize(GameListener(), config)
          }
      }
  """
          .trimIndent()

  fun mainActivityJava(packageId: String): String =
      """
      package $packageId;

      import android.os.Bundle;
      import com.badlogic.gdx.backends.android.AndroidApplication;
      import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

      /**
       * libGDX 入口 Activity。
       *
       * 生命周期完全交给 [AndroidApplication] 处理——它在 onCreate 里创建
       * SurfaceView、建立 OpenGL 上下文并驱动渲染循环，我们只需要把
       * [GameListener] 交给它。
       */
      public class MainActivity extends AndroidApplication {

          @Override
          protected void onCreate(Bundle savedInstanceState) {
              super.onCreate(savedInstanceState);

              AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
              // 只需要颜色缓冲，不需要深度/模板，省一点内存和带宽
              config.depth = 0;
              config.stencil = 0;
              // 不使用传感器与音频，最小 demo 用不到
              config.useAccelerometer = false;
              config.useGyroscope = false;
              config.useCompass = false;
              config.disableAudio = true;
              // 全屏沉浸，游戏里没有系统栏的位置
              config.useImmersiveMode = true;

              initialize(new GameListener(), config);
          }
      }
  """
          .trimIndent()

  /**
   * 最小可玩 demo：拖动挡板接住下落方块。
   *
   * 玩法闭环（四个状态都有）：
   * - 进行中：方块下落，挡板跟随手指
   * - 得分：接住一个 +1，每 5 分加速，难度递增
   * - 失败：漏掉一个即 GameOver
   * - 重开：GameOver 后点击屏幕重置
   *
   * 触摸坐标来自 libGDX 的 y 轴向上屏幕坐标系，与渲染坐标系一致，
   * 不需要做任何翻转。
   */
  fun gameListenerKotlin(packageId: String): String =
      """
      package $packageId

      import com.badlogic.gdx.ApplicationAdapter
      import com.badlogic.gdx.Gdx
      import com.badlogic.gdx.InputAdapter
      import com.badlogic.gdx.graphics.Color
      import com.badlogic.gdx.graphics.GL20
      import com.badlogic.gdx.graphics.g2d.BitmapFont
      import com.badlogic.gdx.graphics.g2d.SpriteBatch
      import com.badlogic.gdx.graphics.glutils.ShapeRenderer
      import com.badlogic.gdx.math.MathUtils
      import com.badlogic.gdx.math.Vector2
      import com.badlogic.gdx.utils.viewport.ExtendViewport

      /**
       * 最小可玩 demo：左右拖动挡板，接住不断下落的方块。
       *
       * 这是 libGDX 的「渲染循环」教学骨架——把它当作起点，
       * 换成 [com.badlogic.gdx.scenes.scene2d.Stage] 或加贴图都可以。
       */
      class GameListener : ApplicationAdapter() {

          companion object {
              /** 逻辑分辨率。所有坐标都按这套尺寸写，与实际像素无关。 */
              private const val WORLD_WIDTH = 480f
              private const val WORLD_HEIGHT = 800f

              private const val PADDLE_WIDTH = 96f
              private const val PADDLE_HEIGHT = 18f
              private const val PADDLE_BOTTOM_MARGIN = 60f

              private const val BLOCK_SIZE = 40f
              private const val BASE_FALL_SPEED = 260f
              /** 每接住一个方块提速多少 */
              private const val SPEED_GAIN_PER_HIT = 14f
              /** 提速上限，避免快到无法反应 */
              private const val MAX_FALL_SPEED = 900f
              /** 每多少分难度提升一档（仅用于显示） */
              private const val SCORE_PER_LEVEL = 5
          }

          // 渲染资源用可空字段而非 lateinit：GL 上下文创建失败时 create() 不会执行，
          // 此时 dispose() 仍会被调用，lateinit 会在那里抛
          // UninitializedPropertyAccessException，把真正的 GL 错误盖掉。
          private var shapes: ShapeRenderer? = null
          private var batch: SpriteBatch? = null
          private var font: BitmapFont? = null

          // 按逻辑分辨率建立视口，并自动适配任意屏幕宽高比。
          // 用 ExtendViewport 而不是 setToOrtho2D 硬拉伸：后者会把 480x800
          // 铺满 20:9 的屏幕，方块被拉成长方形、挡板相对屏宽也随设备漂移，
          // 难度曲线在不同手机上不一致。
          private val viewport = ExtendViewport(WORLD_WIDTH, WORLD_HEIGHT)

          // 复用的临时向量，避免每帧分配
          private val touchPoint = Vector2()

          private var paddleX = (WORLD_WIDTH - PADDLE_WIDTH) / 2f
          private var blockX = 0f
          private var blockY = 0f
          private var fallSpeed = BASE_FALL_SPEED

          private var score = 0
          private var best = 0
          private var gameOver = false
          private val rng = java.util.Random()

          override fun create() {
            shapes = ShapeRenderer()
            batch = SpriteBatch()
            font = BitmapFont().also {
              // 内置字体只有 15px，放大到适合手机上阅读
              it.data.setScale(2.0f)
            }
            // 首次进入时用当前窗口尺寸初始化视口；之后交给 resize()
            viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)

            // 触摸回调：拖动挡板 / 失败后重开
            Gdx.input.inputProcessor =
                object : InputAdapter() {
                  override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                    if (!gameOver) {
                      movePaddleTo(screenX, screenY)
                    }
                    return true
                  }

                  override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                    if (gameOver) {
                      restart()
                    } else {
                      movePaddleTo(screenX, screenY)
                    }
                    return true
                  }
                }

            spawnBlock()
          }

          /**
           * 把屏幕像素坐标换算成逻辑坐标，再移动挡板。
           *
           * 换算交给视口完成（而不是自己按比例缩放）：ExtendViewport 可能扩展
           * 世界宽度，此时线性缩放会算错。`unproject` 用的是与渲染同一套相机矩阵，
           * 两者必然一致。
           */
          private fun movePaddleTo(screenX: Int, screenY: Int) {
            viewport.unproject(touchPoint.set(screenX.toFloat(), screenY.toFloat()))
            paddleX =
                MathUtils.clamp(
                    touchPoint.x - PADDLE_WIDTH / 2f,
                    0f,
                    viewport.worldWidth - PADDLE_WIDTH,
                )
          }

          private fun spawnBlock() {
            blockX = rng.nextFloat() * (WORLD_WIDTH - BLOCK_SIZE)
            blockY = WORLD_HEIGHT
          }

          private fun restart() {
            score = 0
            fallSpeed = BASE_FALL_SPEED
            gameOver = false
            spawnBlock()
          }

          override fun render() {
            val delta = Math.min(Gdx.graphics.deltaTime, 1f / 30f)

            if (!gameOver) {
              blockY -= fallSpeed * delta

              val paddleTop = PADDLE_BOTTOM_MARGIN + PADDLE_HEIGHT
              val caught = blockY <= paddleTop &&
                  blockY + BLOCK_SIZE >= PADDLE_BOTTOM_MARGIN &&
                  blockX + BLOCK_SIZE >= paddleX &&
                  blockX <= paddleX + PADDLE_WIDTH

              if (caught) {
                score++
                if (score > best) {
                  best = score
                }
                fallSpeed = Math.min(fallSpeed + SPEED_GAIN_PER_HIT, MAX_FALL_SPEED)
                spawnBlock()
              } else if (blockY + BLOCK_SIZE < 0f) {
                // 漏掉了——直接结束这一局
                gameOver = true
              }
            }

            // 视口会按屏幕宽高比扩展世界尺寸（只扩不缩），
            // 所以下面画背景与文字时要用实际世界尺寸，不能再用 WORLD_WIDTH /
            // WORLD_HEIGHT——否则宽屏上会留下未覆盖的边。
            viewport.apply()
            val worldWidth = viewport.worldWidth
            val worldHeight = viewport.worldHeight

            Gdx.gl.glClearColor(0.09f, 0.11f, 0.16f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

            // 画形状
            val shapes = shapes!!
            val batch = batch!!
            shapes.projectionMatrix = viewport.camera.combined
            shapes.begin(ShapeRenderer.ShapeType.Filled)

            // 挡板
            shapes.color = Color.CYAN
            shapes.rect(paddleX, PADDLE_BOTTOM_MARGIN, PADDLE_WIDTH, PADDLE_HEIGHT)

            // 下落方块
            shapes.color = if (gameOver) Color.FIREBRICK else Color.GOLD
            shapes.rect(blockX, blockY, BLOCK_SIZE, BLOCK_SIZE)

            // 底部基准线，给一点纵深感
            shapes.color = Color.DARK_GRAY
            shapes.rect(0f, 0f, worldWidth, PADDLE_BOTTOM_MARGIN - 40f)

            shapes.end()

            // 画文字（必须在 ShapeRenderer.end() 之后，两个批次不能嵌套）
            val font = font!!
            batch.projectionMatrix = viewport.camera.combined
            batch.begin()
            font.color = Color.WHITE
            font.draw(batch, "Score: " + score, 16f, worldHeight - 24f)
            font.draw(batch, "Best: " + best, 16f, worldHeight - 64f)
            font.draw(
                batch,
                "Level " + (score / SCORE_PER_LEVEL + 1),
                16f,
                worldHeight - 104f,
            )

            if (gameOver) {
              font.color = Color.SCARLET
              font.draw(batch, "GAME OVER", worldWidth / 2f - 100f, worldHeight / 2f + 20f)
              font.color = Color.WHITE
              font.draw(batch, "Tap to retry", worldWidth / 2f - 100f, worldHeight / 2f - 30f)
            }

            batch.end()
          }

          /** 窗口尺寸变化时重建视口。不处理的话横竖屏切换后画面会被拉伸。 */
          override fun resize(width: Int, height: Int) {
            viewport.update(width, height, true)
          }

          override fun dispose() {
            shapes?.dispose()
            batch?.dispose()
            font?.dispose()
          }
      }
  """
          .trimIndent()

  fun gameListenerJava(packageId: String): String =
      """
      package $packageId;

      import com.badlogic.gdx.ApplicationAdapter;
      import com.badlogic.gdx.Gdx;
      import com.badlogic.gdx.InputAdapter;
      import com.badlogic.gdx.graphics.Color;
      import com.badlogic.gdx.graphics.GL20;
      import com.badlogic.gdx.graphics.g2d.BitmapFont;
      import com.badlogic.gdx.graphics.g2d.SpriteBatch;
      import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
      import com.badlogic.gdx.math.MathUtils;
      import com.badlogic.gdx.math.Vector2;
      import com.badlogic.gdx.utils.viewport.ExtendViewport;
      import java.util.Random;

      /**
       * 最小可玩 demo：左右拖动挡板，接住不断下落的方块。
       *
       * 这是 libGDX 的「渲染循环」教学骨架——把它当作起点，
       * 换成 Stage 或加贴图都可以。
       */
      public class GameListener extends ApplicationAdapter {

          /** 逻辑分辨率。所有坐标都按这套尺寸写，与实际像素无关。 */
          private static final float WORLD_WIDTH = 480f;
          private static final float WORLD_HEIGHT = 800f;

          private static final float PADDLE_WIDTH = 96f;
          private static final float PADDLE_HEIGHT = 18f;
          private static final float PADDLE_BOTTOM_MARGIN = 60f;

          private static final float BLOCK_SIZE = 40f;
          private static final float BASE_FALL_SPEED = 260f;
          /** 每接住一个方块提速多少 */
          private static final float SPEED_GAIN_PER_HIT = 14f;
          /** 提速上限，避免快到无法反应 */
          private static final float MAX_FALL_SPEED = 900f;
          /** 每多少分难度提升一档（仅用于显示） */
          private static final int SCORE_PER_LEVEL = 5;

          // 渲染资源用可空字段而非直接初始化：GL 上下文创建失败时 create()
          // 不会执行，此时 dispose() 仍会被调用，需要容忍 null。
          private ShapeRenderer shapes;
          private SpriteBatch batch;
          private BitmapFont font;

          // 按逻辑分辨率建立视口，并自动适配任意屏幕宽高比。
          // 用 ExtendViewport 而不是 setToOrtho2D 硬拉伸：后者会把 480x800
          // 铺满 20:9 的屏幕，方块被拉成长方形、挡板相对屏宽也随设备漂移，
          // 难度曲线在不同手机上不一致。
          private final ExtendViewport viewport =
                  new ExtendViewport(WORLD_WIDTH, WORLD_HEIGHT);

          // 复用的临时向量，避免每帧分配
          private final Vector2 touchPoint = new Vector2();

          private float paddleX = (WORLD_WIDTH - PADDLE_WIDTH) / 2f;
          private float blockX;
          private float blockY;
          private float fallSpeed = BASE_FALL_SPEED;

          private int score;
          private int best;
          private boolean gameOver;
          private final Random rng = new Random();

          @Override
          public void create() {
              shapes = new ShapeRenderer();
              batch = new SpriteBatch();
              font = new BitmapFont();
              // 内置字体只有 15px，放大到适合手机上阅读
              font.getData().setScale(2.0f);

              // 首次进入时用当前窗口尺寸初始化视口；之后交给 resize()
              viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);

              // 触摸回调：拖动挡板 / 失败后重开
              Gdx.input.setInputProcessor(new InputAdapter() {
                  @Override
                  public boolean touchDragged(int screenX, int screenY, int pointer) {
                      if (!gameOver) {
                          movePaddleTo(screenX, screenY);
                      }
                      return true;
                  }

                  @Override
                  public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                      if (gameOver) {
                          restart();
                      } else {
                          movePaddleTo(screenX, screenY);
                      }
                      return true;
                  }
              });

              spawnBlock();
          }

          /**
           * 把屏幕像素坐标换算成逻辑坐标，再移动挡板。
           *
           * 换算交给视口完成（而不是自己按比例缩放）：ExtendViewport 可能扩展
           * 世界宽度，此时线性缩放会算错。`unproject` 用的是与渲染同一套相机矩阵，
           * 两者必然一致。
           */
          private void movePaddleTo(int screenX, int screenY) {
              viewport.unproject(touchPoint.set(screenX, screenY));
              paddleX = MathUtils.clamp(
                      touchPoint.x - PADDLE_WIDTH / 2f,
                      0f,
                      viewport.getWorldWidth() - PADDLE_WIDTH);
          }

          private void spawnBlock() {
              blockX = rng.nextFloat() * (WORLD_WIDTH - BLOCK_SIZE);
              blockY = WORLD_HEIGHT;
          }

          private void restart() {
              score = 0;
              fallSpeed = BASE_FALL_SPEED;
              gameOver = false;
              spawnBlock();
          }

          @Override
          public void render() {
              float delta = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);

              if (!gameOver) {
                  blockY -= fallSpeed * delta;

                  float paddleTop = PADDLE_BOTTOM_MARGIN + PADDLE_HEIGHT;
                  boolean caught = blockY <= paddleTop
                          && blockY + BLOCK_SIZE >= PADDLE_BOTTOM_MARGIN
                          && blockX + BLOCK_SIZE >= paddleX
                          && blockX <= paddleX + PADDLE_WIDTH;

                  if (caught) {
                      score++;
                      if (score > best) {
                          best = score;
                      }
                      fallSpeed = Math.min(fallSpeed + SPEED_GAIN_PER_HIT, MAX_FALL_SPEED);
                      spawnBlock();
                  } else if (blockY + BLOCK_SIZE < 0f) {
                      // 漏掉了——直接结束这一局
                      gameOver = true;
                  }
              }

              // 视口会按屏幕宽高比扩展世界尺寸（只扩不缩），
              // 所以下面画背景与文字时要用实际世界尺寸，不能再用 WORLD_WIDTH /
              // WORLD_HEIGHT——否则宽屏上会留下未覆盖的边。
              viewport.apply();
              float worldWidth = viewport.getWorldWidth();
              float worldHeight = viewport.getWorldHeight();

              Gdx.gl.glClearColor(0.09f, 0.11f, 0.16f, 1f);
              Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

              // 画形状
              shapes.setProjectionMatrix(viewport.getCamera().combined);
              shapes.begin(ShapeRenderer.ShapeType.Filled);

              // 挡板
              shapes.setColor(Color.CYAN);
              shapes.rect(paddleX, PADDLE_BOTTOM_MARGIN, PADDLE_WIDTH, PADDLE_HEIGHT);

              // 下落方块
              shapes.setColor(gameOver ? Color.FIREBRICK : Color.GOLD);
              shapes.rect(blockX, blockY, BLOCK_SIZE, BLOCK_SIZE);

              // 底部基准线，给一点纵深感
              shapes.setColor(Color.DARK_GRAY);
              shapes.rect(0f, 0f, worldWidth, PADDLE_BOTTOM_MARGIN - 40f);

              shapes.end();

              // 画文字（必须在 ShapeRenderer.end() 之后，两个批次不能嵌套）
              batch.setProjectionMatrix(viewport.getCamera().combined);
              batch.begin();
              font.setColor(Color.WHITE);
              font.draw(batch, "Score: " + score, 16f, worldHeight - 24f);
              font.draw(batch, "Best: " + best, 16f, worldHeight - 64f);
              font.draw(batch, "Level " + (score / SCORE_PER_LEVEL + 1), 16f, worldHeight - 104f);

              if (gameOver) {
                  font.setColor(Color.SCARLET);
                  font.draw(batch, "GAME OVER", worldWidth / 2f - 100f, worldHeight / 2f + 20f);
                  font.setColor(Color.WHITE);
                  font.draw(batch, "Tap to retry", worldWidth / 2f - 100f, worldHeight / 2f - 30f);
              }

              batch.end();
          }

          /** 窗口尺寸变化时重建视口。不处理的话横竖屏切换后画面会被拉伸。 */
          @Override
          public void resize(int width, int height) {
              viewport.update(width, height, true);
          }

          @Override
          public void dispose() {
              if (shapes != null) {
                  shapes.dispose();
              }
              if (batch != null) {
                  batch.dispose();
              }
              if (font != null) {
                  font.dispose();
              }
          }
      }
  """
          .trimIndent()
}
