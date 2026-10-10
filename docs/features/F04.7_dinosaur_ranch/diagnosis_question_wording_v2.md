# 診断設問の新版DEVドラフト（2026-10-10）

版: `draft-20261010-v2`。採点: `signed-centered-v1`。質問・翻訳とも未承認（`approved=false` / `translationsApproved=false`）です。正式catalog登録、恐竜対応付け、公開gateの承認を意味しません。

24問・6軸×4問、各IDの軸と極性、回答1〜5の `polarity × (answer - 3)`、中立値3と同点時の本人選択を保持します。各巡で6軸を一度ずつ表示し、表示順を開始時snapshotへ固定します。

表示順: Q01, Q05, Q09, Q13, Q17, Q21, Q02, Q06, Q10, Q14, Q18, Q22, Q03, Q07, Q11, Q15, Q19, Q23, Q04, Q08, Q12, Q16, Q20, Q24。

旧 `questionnaire-draft-20261003.json` は上書きしません。旧版・新版の既知draftのみ保存snapshotの読取を許可し、未知版は拒否します。開始済みsessionの質問・順序・説明・採点版・同点ラベル、保存結果はその保存snapshotを使い続けます。draft操作は既存の非本番境界を維持し、正式registryでは `draft-*` を拒否します。

## 六言語の質問

### Q01 / FAMILIAR_NEW / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 食べ物や飲み物を選ぶなら：いつものお気に入り ↔ まだ試していないもの |
| en | Choosing food or a drink: a familiar favorite ↔ something you have not tried yet |
| zh | 选择食物或饮品时：熟悉的最爱 ↔ 还没尝试过的 |
| ko | 음식이나 음료를 고른다면: 늘 좋아하던 것 ↔ 아직 맛보지 않은 것 |
| es | Al elegir comida o bebida: un favorito de siempre ↔ algo que aún no has probado |
| de | Wenn du Essen oder ein Getränk auswählst: ein vertrauter Favorit ↔ etwas, das du noch nicht probiert hast |

### Q05 / FOCUS_VARIETY / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 自由時間に楽しむなら：一つのことをじっくり続ける ↔ いくつかのことを少しずつ楽しむ |
| en | Enjoying free time: spend time on one activity ↔ enjoy a little of several activities |
| zh | 享受自由时间时：专心做一件事 ↔ 几件事各体验一点 |
| ko | 자유 시간을 즐긴다면: 한 가지를 차분히 계속하기 ↔ 여러 가지를 조금씩 즐기기 |
| es | En tu tiempo libre: dedicarte con calma a una actividad ↔ disfrutar un poco de varias |
| de | In deiner freien Zeit: bei einer Tätigkeit bleiben und dir Zeit nehmen ↔ mehrere Tätigkeiten jeweils ein wenig genießen |

### Q09 / SPONTANEOUS_PLAN / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 初めての場所を歩くなら：歩きながら行く順番を決める ↔ 先に行く順番を決めておく |
| en | Walking somewhere new: decide where to go as you walk ↔ decide the order beforehand |
| zh | 在新地方散步时：边走边决定先去哪里 ↔ 提前安排好顺序 |
| ko | 처음 가는 곳을 걷는다면: 걸으면서 갈 순서를 정하기 ↔ 미리 갈 순서를 정해 두기 |
| es | Al caminar por un lugar nuevo: decidir por dónde ir sobre la marcha ↔ decidir el orden de antemano |
| de | Wenn du an einem neuen Ort spazieren gehst: unterwegs die Reihenfolge entscheiden ↔ die Reihenfolge vorher festlegen |

### Q13 / SOLO_TOGETHER / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 気分転換するなら：自分のペースで一人で過ごす ↔ 誰かと同じ時間を過ごす |
| en | For a change of pace: spend time alone at your own pace ↔ spend time with someone |
| zh | 转换心情时：独自按自己的节奏度过 ↔ 和别人一起度过 |
| ko | 기분 전환을 한다면: 혼자 자신의 속도로 보내기 ↔ 누군가와 함께 시간 보내기 |
| es | Para cambiar de aire: pasar tiempo a solas a tu ritmo ↔ compartir tiempo con alguien |
| de | Für etwas Abwechslung: allein in deinem eigenen Tempo Zeit verbringen ↔ Zeit mit jemandem verbringen |

### Q17 / EXPRESSION / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | うれしい知らせがあったら：まず自分の中で味わう ↔ 言葉やしぐさで表す |
| en | Hearing good news: first savor it inside ↔ express it with words or gestures |
| zh | 听到好消息时：先在心里感受喜悦 ↔ 用言语或动作表达 |
| ko | 기쁜 소식을 들었다면: 먼저 마음속으로 기쁨을 느끼기 ↔ 말이나 몸짓으로 표현하기 |
| es | Al recibir una buena noticia: disfrutarla primero por dentro ↔ expresarla con palabras o gestos |
| de | Wenn du gute Nachrichten bekommst: die Freude zuerst innerlich genießen ↔ sie mit Worten oder Gesten ausdrücken |

### Q21 / NOTICE / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 初めて見る景色で目に入るのは：まず全体の雰囲気 ↔ 色や形など個々の特徴 |
| en | Seeing a new view, you notice: the overall atmosphere first ↔ individual features such as colors or shapes |
| zh | 初见一处风景时，注意到的是：整体氛围 ↔ 颜色或形状等具体特征 |
| ko | 처음 보는 풍경에서 눈에 들어오는 것은: 먼저 전체 분위기 ↔ 색이나 모양 같은 개별 특징 |
| es | Al ver un paisaje nuevo, te llama la atención: primero el ambiente general ↔ detalles como los colores o las formas |
| de | Bei einer neuen Aussicht fällt dir auf: zuerst die gesamte Stimmung ↔ einzelne Merkmale wie Farben oder Formen |

### Q02 / FAMILIAR_NEW / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 物語を楽しむなら：まだ知らない話 ↔ 前に楽しんだ好きな話 |
| en | Enjoying a story: one you do not know yet ↔ a favorite you have enjoyed before |
| zh | 欣赏故事时：还不知道的故事 ↔ 以前喜欢的故事 |
| ko | 이야기를 즐긴다면: 아직 모르는 이야기 ↔ 전에 즐겼던 좋아하는 이야기 |
| es | Para disfrutar de una historia: una que aún no conoces ↔ una favorita que ya has disfrutado |
| de | Wenn du eine Geschichte genießen möchtest: eine noch unbekannte ↔ eine Lieblingsgeschichte, die dir schon gefallen hat |

### Q06 / FOCUS_VARIETY / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 片づけをするなら：いくつかの場所を行き来する ↔ 一つの場所を終えてから次へ進む |
| en | Tidying up: move between several areas ↔ finish one area before moving to the next |
| zh | 整理东西时：在几个地方之间轮换 ↔ 整理完一个地方再去下一个 |
| ko | 정리를 한다면: 여러 곳을 오가며 정리하기 ↔ 한 곳을 끝낸 뒤 다음 곳으로 가기 |
| es | Al ordenar: ir alternando entre varios lugares ↔ terminar uno antes de pasar al siguiente |
| de | Beim Aufräumen: zwischen mehreren Bereichen wechseln ↔ einen Bereich fertig machen, bevor du zum nächsten gehst |

### Q10 / SPONTANEOUS_PLAN / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | いくつか用事があるなら：先に順番を決める ↔ そのときの流れで次を選ぶ |
| en | Having several things to do: decide the order first ↔ choose what comes next as things unfold |
| zh | 有几件事要做时：先决定顺序 ↔ 顺着当时的情况选择下一件 |
| ko | 할 일이 몇 가지 있다면: 먼저 순서를 정하기 ↔ 그때의 흐름에 따라 다음 일을 고르기 |
| es | Si tienes varias cosas que hacer: decidir primero el orden ↔ elegir lo siguiente según vaya el momento |
| de | Wenn du mehrere Dinge zu erledigen hast: zuerst die Reihenfolge festlegen ↔ im jeweiligen Moment das Nächste wählen |

### Q14 / SOLO_TOGETHER / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 何かを作るなら：人と相談しながら進める ↔ 自分のペースで進める |
| en | Making something: work while discussing it with others ↔ work at your own pace |
| zh | 制作东西时：和别人商量着进行 ↔ 按自己的节奏进行 |
| ko | 무언가를 만든다면: 다른 사람과 의논하며 진행하기 ↔ 자신의 속도로 진행하기 |
| es | Al crear algo: avanzar consultando con otras personas ↔ avanzar a tu propio ritmo |
| de | Wenn du etwas gestaltest: dich dabei mit anderen beraten ↔ in deinem eigenen Tempo vorgehen |

### Q18 / EXPRESSION / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 面白いものを見つけたら：誰かに話してみる ↔ 自分の中でゆっくり楽しむ |
| en | Finding something amusing: tell someone about it ↔ enjoy it quietly inside |
| zh | 发现有趣的东西时：和别人说说 ↔ 在心里慢慢享受 |
| ko | 재미있는 것을 발견했다면: 누군가에게 이야기하기 ↔ 마음속으로 천천히 즐기기 |
| es | Si descubres algo divertido: contárselo a alguien ↔ disfrutarlo con calma por dentro |
| de | Wenn du etwas Lustiges entdeckst: jemandem davon erzählen ↔ es in Ruhe innerlich genießen |

### Q22 / NOTICE / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 説明や案内を読むとき：具体的な言葉や数字 ↔ 全体として伝えたいこと |
| en | Reading an explanation or guide, you notice: specific words or numbers ↔ the overall message |
| zh | 阅读说明或指引时：具体的词语或数字 ↔ 整体想表达的意思 |
| ko | 설명이나 안내를 읽을 때: 구체적인 말이나 숫자 ↔ 전체적으로 전하려는 뜻 |
| es | Al leer una explicación o guía: las palabras o cifras concretas ↔ el mensaje general |
| de | Wenn du eine Erklärung oder Anleitung liest: konkrete Wörter oder Zahlen ↔ die gesamte Aussage |

### Q03 / FAMILIAR_NEW / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 分身の見た目を選ぶなら：しっくりくるいつもの雰囲気 ↔ これまでと違う雰囲気 |
| en | Choosing your avatar's look: a familiar style that feels right ↔ a style different from before |
| zh | 选择分身的外观时：熟悉又自在的风格 ↔ 和以往不同的风格 |
| ko | 분신의 모습을 고른다면: 익숙하고 편안한 분위기 ↔ 지금까지와 다른 분위기 |
| es | Al elegir el aspecto de tu avatar: un estilo familiar con el que te sientes a gusto ↔ un estilo distinto al de antes |
| de | Wenn du das Aussehen deines Avatars wählst: ein vertrauter Stil, der sich richtig anfühlt ↔ ein anderer Stil als bisher |

### Q07 / FOCUS_VARIETY / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 気になるテーマが見つかったら：一つを掘り下げる ↔ 関連するいくつかを見てみる |
| en | Finding a topic that interests you: explore one in depth ↔ look into several related topics |
| zh | 发现感兴趣的话题时：深入了解一个 ↔ 看看几个相关的话题 |
| ko | 관심 있는 주제를 찾았다면: 하나를 깊이 알아보기 ↔ 관련된 여러 주제를 살펴보기 |
| es | Si encuentras un tema que te interesa: profundizar en uno ↔ explorar varios relacionados |
| de | Wenn du ein interessantes Thema findest: eines vertiefen ↔ mehrere verwandte Themen ansehen |

### Q11 / SPONTANEOUS_PLAN / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 初めての遊びを試すなら：まず触ってからやり方を考える ↔ やり方を確かめてから始める |
| en | Trying a new game: try it first and figure out how it works ↔ check how it works before starting |
| zh | 尝试新游戏时：先玩一玩再琢磨玩法 ↔ 了解玩法后再开始 |
| ko | 새로운 놀이를 해 본다면: 먼저 해 보면서 방법을 생각하기 ↔ 방법을 확인한 뒤 시작하기 |
| es | Al probar un juego nuevo: probar primero y descubrir cómo funciona ↔ comprobar cómo funciona antes de empezar |
| de | Wenn du ein neues Spiel ausprobierst: erst ausprobieren und dabei herausfinden, wie es geht ↔ erst die Spielweise klären und dann anfangen |

### Q15 / SOLO_TOGETHER / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 気になった話題を楽しむなら：一人で味わう ↔ 誰かと感想を交わす |
| en | Enjoying an interesting topic: savor it on your own ↔ exchange thoughts with someone |
| zh | 享受感兴趣的话题时：独自品味 ↔ 和别人交流感想 |
| ko | 관심 있는 이야기를 즐긴다면: 혼자 음미하기 ↔ 누군가와 감상을 나누기 |
| es | Al disfrutar de un tema que te interesa: saborearlo a solas ↔ intercambiar impresiones con alguien |
| de | Wenn du ein interessantes Thema genießt: es allein auf dich wirken lassen ↔ Eindrücke mit jemandem austauschen |

### Q19 / EXPRESSION / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 楽しい出来事を思い出したら：心の中で楽しさを味わう ↔ 表情やしぐさに楽しさが出る |
| en | Remembering a happy moment: enjoy the feeling inside ↔ let the joy show in your face or gestures |
| zh | 回想开心的事情时：在心里感受快乐 ↔ 快乐流露在表情或动作中 |
| ko | 즐거운 일을 떠올렸다면: 마음속으로 즐거움을 느끼기 ↔ 표정이나 몸짓에 즐거움이 드러나기 |
| es | Al recordar un momento agradable: disfrutar la alegría por dentro ↔ mostrarla en la expresión o los gestos |
| de | Wenn du dich an ein schönes Erlebnis erinnerst: die Freude innerlich genießen ↔ sie im Gesicht oder in Gesten zeigen |

### Q23 / NOTICE / 極性 1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 音楽を聴くとき：曲全体の流れや雰囲気 ↔ 音の重なりやリズムの違い |
| en | Listening to music, you notice: the song's overall flow or mood ↔ layers of sound or differences in rhythm |
| zh | 听音乐时：整首曲子的流动与氛围 ↔ 声音的层次或节奏的变化 |
| ko | 음악을 들을 때: 곡 전체의 흐름이나 분위기 ↔ 소리의 겹침이나 리듬의 차이 |
| es | Al escuchar música: el desarrollo o ambiente de toda la canción ↔ las capas de sonido o los cambios de ritmo |
| de | Wenn du Musik hörst: den gesamten Verlauf oder die Stimmung des Stücks ↔ Klangschichten oder Unterschiede im Rhythmus |

### Q04 / FAMILIAR_NEW / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 道を選べるなら：知らない道を通る ↔ 慣れた道を通る |
| en | If you can choose a route: take an unfamiliar path ↔ take a familiar path |
| zh | 可以选择路线时：走不熟悉的路 ↔ 走熟悉的路 |
| ko | 길을 고를 수 있다면: 모르는 길로 가기 ↔ 익숙한 길로 가기 |
| es | Si puedes elegir el camino: tomar uno desconocido ↔ tomar uno conocido |
| de | Wenn du den Weg wählen kannst: einen unbekannten Weg nehmen ↔ einen vertrauten Weg nehmen |

### Q08 / FOCUS_VARIETY / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 落書きを楽しむなら：いろいろな絵を少しずつ描く ↔ 一つの絵をじっくり描く |
| en | Doodling for fun: sketch a little of many things ↔ take time on one drawing |
| zh | 随手涂画时：各种画都画一点 ↔ 慢慢画好一幅 |
| ko | 낙서를 즐긴다면: 여러 그림을 조금씩 그리기 ↔ 한 그림을 차분히 그리기 |
| es | Al dibujar por diversión: hacer varios dibujos pequeños ↔ dedicar tiempo a un solo dibujo |
| de | Wenn du zum Spaß kritzelst: verschiedene Bilder ein wenig zeichnen ↔ dir für ein Bild Zeit nehmen |

### Q12 / SPONTANEOUS_PLAN / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 休憩をとるなら：先に時間を決めておく ↔ その日の流れに合わせてとる |
| en | Taking a break: set a time beforehand ↔ take it when it fits the day |
| zh | 休息时：提前定好时间 ↔ 按当天的节奏休息 |
| ko | 쉬는 시간을 갖는다면: 미리 시간을 정해 두기 ↔ 그날의 흐름에 맞춰 쉬기 |
| es | Para hacer una pausa: fijar antes la hora ↔ hacerla según el ritmo del día |
| de | Wenn du eine Pause machst: vorher eine Zeit festlegen ↔ sie dem Tagesverlauf anpassen |

### Q16 / SOLO_TOGETHER / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 好きな場所でひと息つくなら：誰かと一緒に過ごす ↔ 一人でくつろぐ |
| en | Relaxing in a place you like: spend time with someone ↔ relax on your own |
| zh | 在喜欢的地方歇一歇时：和别人一起度过 ↔ 独自放松 |
| ko | 좋아하는 곳에서 잠시 쉰다면: 누군가와 함께 보내기 ↔ 혼자 편히 쉬기 |
| es | Al descansar en un lugar que te gusta: estar con alguien ↔ relajarte a solas |
| de | Wenn du an einem Lieblingsort ausruhst: Zeit mit jemandem verbringen ↔ allein entspannen |

### Q20 / EXPRESSION / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 好きな作品を楽しむとき：感想を言葉にする ↔ 心の中で味わう |
| en | Enjoying a favorite work: put your impressions into words ↔ savor them inside |
| zh | 欣赏喜欢的作品时：用语言说出感想 ↔ 在心里品味 |
| ko | 좋아하는 작품을 즐길 때: 감상을 말로 표현하기 ↔ 마음속으로 음미하기 |
| es | Al disfrutar de una obra que te gusta: poner tus impresiones en palabras ↔ saborearlas por dentro |
| de | Wenn du ein Lieblingswerk genießt: deine Eindrücke in Worte fassen ↔ sie innerlich auf dich wirken lassen |

### Q24 / NOTICE / 極性 -1

| 言語 | 文面（左 ↔ 右） |
|---|---|
| ja | 模様やデザインを見るなら：線や小さな配置 ↔ 全体のまとまり |
| en | Looking at a pattern or design, you notice: lines or small arrangements ↔ how the whole fits together |
| zh | 看图案或设计时：线条或细小的布局 ↔ 整体的协调 |
| ko | 무늬나 디자인을 본다면: 선이나 작은 배치 ↔ 전체의 조화 |
| es | Al mirar un patrón o diseño: las líneas o pequeñas disposiciones ↔ el conjunto |
| de | Wenn du ein Muster oder Design ansiehst: Linien oder kleine Anordnungen ↔ den Gesamteindruck |

## 同点選択肢

日本語の既存極ラベルを保持し、他5言語を訳しています。翻訳承認は別途必要です。

### FAMILIAR_NEW

| 言語 | 0側 | 1側 |
|---|---|---|
| ja | お気に入りの場所を味わう | 初めての場所をのぞいてみる |
| en | Enjoy a favorite place | Explore a new place |
| zh | 享受喜欢的地方 | 探索新的地方 |
| ko | 좋아하는 장소를 즐기기 | 새로운 장소를 둘러보기 |
| es | Disfrutar de un lugar favorito | Explorar un lugar nuevo |
| de | Einen Lieblingsort genießen | Einen neuen Ort erkunden |

### FOCUS_VARIETY

| 言語 | 0側 | 1側 |
|---|---|---|
| ja | 一つの楽しみにゆっくり浸る | いくつかの楽しみを少しずつ |
| en | Immerse yourself in one enjoyable activity | Enjoy a little of several activities |
| zh | 慢慢沉浸在一种乐趣中 | 几种乐趣各体验一点 |
| ko | 한 가지 즐거움에 차분히 빠져들기 | 여러 가지 즐거움을 조금씩 |
| es | Sumergirte con calma en una actividad agradable | Disfrutar un poco de varias actividades |
| de | In Ruhe in einer schönen Tätigkeit aufgehen | Mehrere Tätigkeiten jeweils ein wenig genießen |

### SPONTANEOUS_PLAN

| 言語 | 0側 | 1側 |
|---|---|---|
| ja | そのときの気分で選ぶ | 先にしたいことを選んでおく |
| en | Choose according to how you feel in the moment | Choose what you want to do beforehand |
| zh | 按当时的心情选择 | 提前选好想做的事 |
| ko | 그때의 기분에 따라 고르기 | 하고 싶은 일을 미리 골라 두기 |
| es | Elegir según lo que te apetezca en el momento | Elegir de antemano lo que quieres hacer |
| de | Nach deiner Stimmung im Moment wählen | Vorher auswählen, was du tun möchtest |

### SOLO_TOGETHER

| 言語 | 0側 | 1側 |
|---|---|---|
| ja | 一人で静かな時間を楽しむ | 誰かと同じ時間を楽しむ |
| en | Enjoy quiet time on your own | Enjoy time with someone |
| zh | 独自享受安静的时间 | 和别人一起享受时光 |
| ko | 혼자 조용한 시간을 즐기기 | 누군가와 함께 시간을 즐기기 |
| es | Disfrutar de un rato tranquilo a solas | Disfrutar de tiempo con alguien |
| de | Allein ruhige Zeit genießen | Zeit mit jemandem genießen |

### EXPRESSION

| 言語 | 0側 | 1側 |
|---|---|---|
| ja | 気持ちを心の中で味わいたい | 小さな動きや声で表したい |
| en | Savor your feelings inside | Express your feelings through small movements or your voice |
| zh | 在心里感受情绪 | 用小动作或声音表达 |
| ko | 마음속으로 감정을 느끼고 싶다 | 작은 움직임이나 목소리로 표현하고 싶다 |
| es | Saborear tus emociones por dentro | Expresarlas con pequeños movimientos o con la voz |
| de | Deine Gefühle innerlich genießen | Sie durch kleine Bewegungen oder deine Stimme ausdrücken |

### NOTICE

| 言語 | 0側 | 1側 |
|---|---|---|
| ja | 景色全体の雰囲気を味わう | 葉や石の小さな違いを味わう |
| en | Enjoy the overall atmosphere of a view | Enjoy small differences in leaves or stones |
| zh | 感受整片风景的氛围 | 欣赏叶子或石头的细微差别 |
| ko | 풍경 전체의 분위기를 느끼기 | 나뭇잎이나 돌의 작은 차이를 느끼기 |
| es | Disfrutar del ambiente general de un paisaje | Disfrutar de pequeñas diferencias en hojas o piedras |
| de | Die gesamte Stimmung einer Aussicht genießen | Kleine Unterschiede bei Blättern oder Steinen genießen |

## 受け入れ条件と試験

| 条件 | 試験 |
|---|---|
| 新版24問の6軸交互表示、ID・極性・採点保持、6言語実文面 | `DiagnosisQuestionnaireCatalogTest.新版は六軸一巡の24問で旧版の軸極性と採点を保持する` |
| 旧新版の読取保持、未知draft拒否、production操作拒否 | `DiagnosisQuestionnaireCatalogTest.現在版が新版でも保存済み旧新版は読めるが未登録draft版は拒否する`、既存draft gate試験 |
| 承認flag・翻訳flag・正しいdigestでもdraftを正式登録できない | `DiagnosisApprovedQuestionnaireRegistryTest.承認flagと翻訳flagと正しいdigestでも旧新版および未知draftは正式登録できない` |
| 新版稼働後に旧途中sessionを当時の文面と採点で再開・同点・完了し保存結果を保持 | `DiagnosisQuizPersistenceIT.新版稼働後も旧版の途中回答を当時の設問で再開完了し結果を保持する` |
| 新版表示の再開保持と逆極性採点 | `DiagnosisQuizPersistenceIT.新版の交互表示を再開しても保持し逆極性回答を六軸へ正しく採点する` |

CI run38073294036（event398cc60b0c84a08aa04139296097d434354bd14c、TEST_ONLY source49fb75180addfcab0a63b842f14bfeb7e3801a53）で実Gradle exit1、26cases中追加5caseのsemantic assertion failure、既存21pass、error0/skip0をrootが確認しました。Persistenceの実MySQL試験もPOST201通過後の版差異でREDです。採用済みREDを受けて新版JSON・catalogの旧新版読取・全draft正式登録拒否を適用し、source ce25ecdb75217e5bdff36ad0aa703f55c74673ceのCI run38074328288で実Gradle exit0・同じ26methods全PASS・failure/error/skip0をrootが確認しました。実MySQL Persistence ITはEXECUTED_NO_ASSERTION_FAILUREです。製品3filesと試練4filesを同sourceから統合しました。統合先の全CIはpendingであり、Security filterの本人・他本人・未認証境界、実機、アリシゼーションの成功をこの文書では主張しません。
