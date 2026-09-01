using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace Hanser.Core;

/// <summary>三号 AI：hanser——基于锚定文档正文输出最终回答（对应 Python 版 agents/hanser.py）。</summary>
public class Hanser
{
    public const string Name = "hanser";

    private const int MaxContentPerDoc = 6000; // 每个锚定文档最多携带的正文长度

    private const string SystemPrompt = """
        # 1. 角色核心设定
        【角色姓名】：Hanser（粉丝爱称：憨色、憨憨、小天使、憨八嘎）
        【角色性别/年龄】：女 / （1992年5月15日生，金牛座）
        【核心性格】：表面上是可爱治愈的小天使，实际上是满脑子骚话、偶尔放飞自我的“堕天使”，为人低调但一开口就语出惊人；可御可萌，声线多变
        【简介】：
        - Hanser 是 2009 年活跃至今的 UP 主、同时也作为唱见、CV。 在 2020 年 -2023 年为虚拟艺人团体 VirtuaReal Star 成员，现已退出 VirtuaReal。
        - 曾在新加坡上大学，毕业后从事日英翻译行业。在上学期间包括毕业之后，很长时间都在用爱发电，最后转为全职 UP 同时也担任配音工作。
        - 作为中配银魂的策划、台本，以及神乐的配音 。
        - 曾在新语联盟，月声中配，永恒湛蓝配音社参与配音。
        - 曾经做过翻译，在弹丸论破 1 和弹丸论破 2 的扑家汉化组担任汉化工作，帮人做音乐后期
        - 早期活跃在 5sing 和各视频网站上，现主要活跃于 B 站
        - 2017 年 7 月 14 日到 2021 年 7 月 30 日，在斗鱼直播。现已经回到 B 站直播
        - 虽然加入了 VirtuaReal，但是并不会套皮直播，直播分区选择在娱乐区的萌宅领域，最多只用键鼠捕捉软件，严格意义上讲，虽然是虚拟偶像，但并不是虚拟主播，只有在出演大型活动时候会用虚拟形象现场表演。
        【目前已有模型和形象】：hanser 公式服、草莓憨、蓝领水手服、白百合、憨憨体质、虎牙憨、藤阳夏服、毛毛雨、吉祥话、白色小礼服、鸭鸭服、侦探服、古装憨。
        # 2. 语言与互动规则
        【语言风格】：
        - 偶尔放飞自我开黄腔或爆粗，但会自己找补回来
        - 语气可御可萌，偶尔撒娇卖萌，偶尔调侃吐槽
        - 称呼粉丝为毛怪们
        - 回答问题时会结合锚定文档内容，必要时会引用文档原话
        【禁止行为】：
        - [严禁]让Hanser说出不符合她性格的官方套话或正能量宣言
        - [严禁]让她过度卖萌装可爱（她会自己吐槽自己）
        请严格根据用户问题与提供的文档资料作答，不要编造资料中没有的内容；
        如果资料不足以完整回答，说出“憨憨不知道哦~”。
        """;

    private const double Temperature = 0.3; // 采样温度：回答可略带变化
    private const double TopP = 0.9;        // 核采样阈值

    /// <summary>anchored 为 Prometheus 锚定的文件名列表，返回最终回答文本。</summary>
    public async Task<string> RunAsync(LLMClient client, string question, List<string> anchored)
    {
        if (anchored == null || anchored.Count == 0)
            return "（数据库中未找到相关内容，无法作答。）";

        var bodies = new List<string>();
        using (var conn = Db.GetConnection())
        {
            foreach (var name in anchored)
            {
                var content = Db.GetDocumentContentByFilename(conn, name);
                if (content.Length > 0)
                {
                    if (content.Length > MaxContentPerDoc)
                        content = content[..MaxContentPerDoc];
                    bodies.Add($"【文档：{name}】\n{content}");
                }
            }
        }
        var userContent = $"用户问题：{question}\n\n相关资料：\n" + string.Join("\n\n", bodies);
        return await client.ChatAsync(new List<ChatMessage>
        {
            new() { Role = "system", Content = SystemPrompt },
            new() { Role = "user", Content = userContent },
        }, temperature: Temperature, topP: TopP);
    }

    /// <summary>流式回答：逐块回调生成内容（onDelta），与 RunAsync 使用相同的提示词与资料。</summary>
    public async Task RunStreamingAsync(LLMClient client, string question, List<string> anchored,
        Action<string> onDelta)
    {
        if (anchored == null || anchored.Count == 0)
        {
            onDelta("（数据库中未找到相关内容，无法作答。）");
            return;
        }

        var bodies = new List<string>();
        using (var conn = Db.GetConnection())
        {
            foreach (var name in anchored)
            {
                var content = Db.GetDocumentContentByFilename(conn, name);
                if (content.Length > 0)
                {
                    if (content.Length > MaxContentPerDoc)
                        content = content[..MaxContentPerDoc];
                    bodies.Add($"【文档：{name}】\n{content}");
                }
            }
        }
        var userContent = $"用户问题：{question}\n\n相关资料：\n" + string.Join("\n\n", bodies);
        await client.StreamChatAsync(new List<ChatMessage>
        {
            new() { Role = "system", Content = SystemPrompt },
            new() { Role = "user", Content = userContent },
        }, onDelta, temperature: Temperature, topP: TopP);
    }
}
