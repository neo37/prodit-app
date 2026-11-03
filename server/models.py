import uuid
import hashlib
import time
from django.db import models
from django.utils import timezone
from django.utils.text import slugify


class MapPost(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    created_at = models.DateTimeField(default=timezone.now)
    user_id_ext = models.CharField(max_length=100, blank=True)
    username = models.CharField(max_length=100, blank=True)
    first_name = models.CharField(max_length=100, blank=True)
    lat = models.FloatField()
    lng = models.FloatField()
    text = models.TextField(blank=True)
    image = models.CharField(max_length=255, blank=True)
    price = models.FloatField(null=True, blank=True)
    is_quest_item = models.BooleanField(default=False)
    quest_id = models.CharField(max_length=50, blank=True)
    step_number = models.IntegerField(null=True, blank=True)
    question_text = models.TextField(blank=True)
    correct_answer = models.CharField(max_length=255, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.username}: {self.text[:40]}"

    @property
    def correct_answer_hash(self):
        if self.correct_answer:
            return hashlib.sha256(
                self.correct_answer.lower().strip().encode()
            ).hexdigest()
        return ""


class Quest(models.Model):
    id = models.CharField(max_length=32, primary_key=True)
    title = models.CharField(max_length=200)
    title_en = models.CharField(max_length=200, blank=True)
    title_sr = models.CharField(max_length=200, blank=True)
    description = models.TextField(blank=True)
    description_en = models.TextField(blank=True)
    description_sr = models.TextField(blank=True)
    difficulty = models.CharField(max_length=20, default="Средний")
    difficulty_en = models.CharField(max_length=20, default="Medium", blank=True)
    difficulty_sr = models.CharField(max_length=20, default="Srednji", blank=True)
    duration_min = models.IntegerField(default=60)
    image = models.CharField(max_length=255, blank=True)

    def __str__(self):
        return self.title

    @property
    def steps_count(self):
        return MapPost.objects.filter(quest_id=self.id, is_quest_item=True).count()


class Challenge(models.Model):
    TYPE_CHOICES = [
        ("selfie", "Selfie"),
        ("shout", "Shout video"),
        ("photo", "Photo"),
    ]
    id = models.CharField(max_length=100, primary_key=True)
    type = models.CharField(max_length=20, choices=TYPE_CHOICES, default="selfie")
    title = models.CharField(max_length=200)
    title_en = models.CharField(max_length=200, blank=True)
    title_sr = models.CharField(max_length=200, blank=True)
    desc = models.TextField(blank=True)
    desc_en = models.TextField(blank=True)
    desc_sr = models.TextField(blank=True)
    location = models.CharField(max_length=100, blank=True)
    location_en = models.CharField(max_length=100, blank=True)
    location_sr = models.CharField(max_length=100, blank=True)
    points = models.IntegerField(default=30)
    lat = models.FloatField()
    lng = models.FloatField()
    active = models.BooleanField(default=True)
    created_at = models.DateTimeField(default=timezone.now)
    created_by_name = models.CharField(max_length=100, blank=True)

    class Meta:
        ordering = ["location", "type"]

    def __str__(self):
        return f"{self.type}: {self.title}"

    @property
    def submission_count(self):
        return self.submissions.count()


class ChallengeSubmission(models.Model):
    STATUS_PENDING  = "pending"
    STATUS_APPROVED = "approved"
    STATUS_REJECTED = "rejected"
    STATUS_CHOICES  = [
        (STATUS_PENDING,  "На проверке"),
        (STATUS_APPROVED, "Утверждено"),
        (STATUS_REJECTED, "Отклонено"),
    ]

    challenge = models.ForeignKey(
        Challenge, on_delete=models.CASCADE, related_name="submissions",
        null=True, blank=True
    )
    session_id    = models.CharField(max_length=64, blank=True)
    display_name  = models.CharField(max_length=100, blank=True)
    file_name     = models.CharField(max_length=300, blank=True)
    created_at    = models.DateTimeField(default=timezone.now)
    points_awarded = models.IntegerField(default=0)
    status        = models.CharField(
        max_length=16, choices=STATUS_CHOICES, default=STATUS_PENDING
    )
    moderated_at  = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.display_name} → {self.challenge_id} [{self.status}]"


class BoardPost(models.Model):
    CATEGORIES = [
        ("dating", "Знакомства / Dating"),
        ("housing", "Жильё / Housing"),
        ("tours", "Экскурсии / Tours"),
        ("services", "Услуги / Services"),
        ("events", "События / Events"),
        ("goods", "Товары / Goods"),
        ("misc", "Разное / Misc"),
    ]
    title = models.CharField(max_length=200)
    body = models.TextField()
    category = models.CharField(max_length=20, choices=CATEGORIES, default="dating")
    contact = models.CharField(max_length=200, blank=True)
    lang = models.CharField(max_length=5, default="ru")
    created_at = models.DateTimeField(default=timezone.now)
    is_approved = models.BooleanField(default=True)
    slug = models.SlugField(max_length=250, unique=True, blank=True)
    views = models.IntegerField(default=0)
    # Поля для импортированных объявлений (парсер)
    price = models.CharField(max_length=80, blank=True)
    location = models.CharField(max_length=120, blank=True)
    image_url = models.URLField(max_length=500, blank=True)
    source = models.CharField(max_length=80, blank=True)
    source_url = models.URLField(max_length=500, blank=True)
    external_id = models.CharField(max_length=120, blank=True, db_index=True)
    # Переводы (базовые title/body — на языке оригинала, lang)
    title_ru = models.CharField(max_length=300, blank=True)
    title_en = models.CharField(max_length=300, blank=True)
    body_ru = models.TextField(blank=True)
    body_en = models.TextField(blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return self.title

    def save(self, *args, **kwargs):
        if not self.slug:
            base = slugify(self.title[:100]) or "post"
            self.slug = f"{base}-{int(time.time()) % 100000}"
        super().save(*args, **kwargs)


class MediaLog(models.Model):
    created_at = models.DateTimeField(default=timezone.now)
    user_id_ext = models.CharField(max_length=50, blank=True)
    username = models.CharField(max_length=100, blank=True)
    first_name = models.CharField(max_length=100, blank=True)
    chat_id = models.CharField(max_length=50, blank=True)
    file_type = models.CharField(max_length=20)
    file_name = models.CharField(max_length=255)
    file_path = models.CharField(max_length=500, blank=True)
    folder = models.CharField(max_length=20, default='archive')

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.username}: {self.file_name}"


class PostResponse(models.Model):
    post = models.ForeignKey(
        MapPost, on_delete=models.CASCADE, related_name="responses"
    )
    user_id_ext = models.CharField(max_length=100, blank=True)
    username = models.CharField(max_length=100, blank=True)
    first_name = models.CharField(max_length=100, blank=True)
    created_at = models.DateTimeField(default=timezone.now)
    messages_json = models.TextField(default="[]")

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"@{self.username} → {self.post_id}"


class GuestUser(models.Model):
    """Anonymous user identified by JWT/UUID stored in localStorage."""
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    display_name = models.CharField(max_length=100, blank=True)
    created_at = models.DateTimeField(default=timezone.now)
    telegram_id = models.BigIntegerField(null=True, blank=True, unique=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return self.display_name or str(self.id)[:8]

    @property
    def total_points(self):
        return ChallengeSubmission.objects.filter(
            session_id=str(self.id)
        ).aggregate(models.Sum("points_awarded"))["points_awarded__sum"] or 0

    @property
    def quest_progress(self):
        return UserProgress.objects.filter(user=self)


class CryptoTreasure(models.Model):
    CURRENCY_CHOICES = [
        ("TON",    "TON"),
        ("USDT",   "USDT TRC-20"),
        ("NOTCOIN","NOT"),
        ("points", "Очки платформы"),
        ("other",  "Другое"),
    ]

    id            = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    created_at    = models.DateTimeField(default=timezone.now)
    expires_at    = models.DateTimeField(null=True, blank=True)

    # --- location ---
    lat           = models.FloatField()
    lng           = models.FloatField()
    radius_m      = models.IntegerField(default=50)

    # --- what's inside ---
    currency      = models.CharField(max_length=20, choices=CURRENCY_CHOICES, default="points")
    amount        = models.DecimalField(max_digits=18, decimal_places=6, default=0)
    message       = models.TextField(blank=True)
    hint          = models.TextField(blank=True)

    # --- creator ---
    creator_name       = models.CharField(max_length=100, blank=True)
    creator_wallet     = models.CharField(max_length=200, blank=True)
    creator_telegram_id = models.BigIntegerField(null=True, blank=True)

    # --- status ---
    is_active     = models.BooleanField(default=True)
    is_claimed    = models.BooleanField(default=False)
    claimed_by_wallet  = models.CharField(max_length=200, blank=True)
    claimed_by_name    = models.CharField(max_length=100, blank=True)
    claimed_at    = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        status = "claimed" if self.is_claimed else "active"
        return f"{self.amount} {self.currency} @ ({self.lat:.4f},{self.lng:.4f}) [{status}]"

    @property
    def is_expired(self):
        if self.expires_at and timezone.now() > self.expires_at:
            return True
        return False


class UserProgress(models.Model):
    user = models.ForeignKey(GuestUser, on_delete=models.CASCADE, related_name="progress")
    quest_id = models.CharField(max_length=50)
    current_step = models.IntegerField(default=1)
    status = models.CharField(
        max_length=20,
        choices=[("in_progress", "In Progress"), ("completed", "Completed")],
        default="in_progress",
    )
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        unique_together = [("user", "quest_id")]

    def __str__(self):
        return f"{self.user} | {self.quest_id} [{self.status}]"


class VisitLog(models.Model):
    """Лог посещений сайта: кто, откуда, что делал."""
    created_at = models.DateTimeField(default=timezone.now, db_index=True)
    ip         = models.CharField(max_length=64, blank=True, db_index=True)
    uid        = models.CharField(max_length=64, blank=True)
    name       = models.CharField(max_length=100, blank=True)
    device     = models.CharField(max_length=40, blank=True)   # Mobile/Desktop/Tablet/Bot
    browser    = models.CharField(max_length=60, blank=True)
    path       = models.CharField(max_length=300, blank=True)
    referer    = models.CharField(max_length=300, blank=True)
    user_agent = models.CharField(max_length=400, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.ip} {self.path}"


class Comment(models.Model):
    """Комментарий к объявлению на доске."""
    board_post = models.ForeignKey(
        BoardPost, on_delete=models.CASCADE, related_name="comments"
    )
    session_id   = models.CharField(max_length=64, blank=True)
    display_name = models.CharField(max_length=100, blank=True)
    text         = models.TextField(max_length=2000)
    created_at   = models.DateTimeField(default=timezone.now)
    is_hidden    = models.BooleanField(default=False)

    class Meta:
        ordering = ["created_at"]

    def __str__(self):
        return f"{self.display_name}: {self.text[:40]}"


class DirectMessage(models.Model):
    """Личное сообщение пользователь→пользователь или пользователь→админ.

    recipient_id == 'admin' — сообщение администратору.
    """
    ADMIN = "admin"
    sender_id      = models.CharField(max_length=64, db_index=True)
    sender_name    = models.CharField(max_length=100, blank=True)
    recipient_id   = models.CharField(max_length=64, db_index=True)
    recipient_name = models.CharField(max_length=100, blank=True)
    text           = models.TextField(max_length=4000)
    created_at     = models.DateTimeField(default=timezone.now)
    is_read        = models.BooleanField(default=False)
    # контекст: из какого объявления начат диалог (необязательно)
    board_slug     = models.CharField(max_length=250, blank=True)

    class Meta:
        ordering = ["created_at"]

    def __str__(self):
        return f"{self.sender_id} → {self.recipient_id}: {self.text[:30]}"


class QrScan(models.Model):
    """Запись о КАЖДОМ переходе по QR-коду /qr/... — сохраняем всю историю."""
    KIND_MAP  = "map"
    KIND_DATE = "date"
    code        = models.CharField(max_length=255, db_index=True)
    kind        = models.CharField(max_length=10, default=KIND_MAP, db_index=True)
    board_key   = models.CharField(max_length=2, blank=True, db_index=True)
    created_at  = models.DateTimeField(default=timezone.now, db_index=True)
    ip          = models.CharField(max_length=64, blank=True)
    user_agent  = models.CharField(max_length=400, blank=True)
    referer     = models.CharField(max_length=300, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.code} @ {self.created_at:%Y-%m-%d %H:%M}"



class ClubBoard(models.Model):
    """Секретная доска клуба. Идентифицируется первыми 2 символами строки QR.

    Каждый новый префикс из 2 символов = новый клуб (без категорий).
    """
    key         = models.CharField(max_length=2, unique=True, db_index=True)
    first_code  = models.CharField(max_length=255, blank=True)
    created_at  = models.DateTimeField(default=timezone.now)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"Клуб [{self.key}]"

    @property
    def posts_count(self):
        return self.posts.filter(is_approved=True).count()


class ClubPost(models.Model):
    """Объявление на доске клуба — без категорий, перевод на 3 языка (RU/EN/SR)."""
    board       = models.ForeignKey(ClubBoard, on_delete=models.CASCADE, related_name="posts")
    title_ru    = models.CharField(max_length=300, blank=True)
    title_en    = models.CharField(max_length=300, blank=True)
    title_sr    = models.CharField(max_length=300, blank=True)
    body_ru     = models.TextField(blank=True)
    body_en     = models.TextField(blank=True)
    body_sr     = models.TextField(blank=True)
    contact     = models.CharField(max_length=200, blank=True)
    lang        = models.CharField(max_length=5, default="ru")
    created_at  = models.DateTimeField(default=timezone.now)
    is_approved = models.BooleanField(default=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"[{self.board.key}] {self.title_ru[:40]}"


# ── Чат (домен kaig.fun) ───────────────────────────────────────────
class ChatRoom(models.Model):
    """Комната чата, доступная по ссылке /r/<code>.

    Один гость (uid из localStorage) может создать ровно одну комнату.
    Пароль необязателен: пустой password_hash = открытая комната.
    """
    code          = models.CharField(max_length=32, unique=True, db_index=True)
    title         = models.CharField(max_length=100)
    owner_uid     = models.CharField(max_length=64, blank=True, db_index=True)
    owner_name    = models.CharField(max_length=100, blank=True)
    password_hash = models.CharField(max_length=64, blank=True)
    created_at    = models.DateTimeField(default=timezone.now)
    last_msg_at   = models.DateTimeField(default=timezone.now, db_index=True)
    is_listed     = models.BooleanField(default=True)
    # комната «удалена» владельцем: скрыта с сайта, но история остаётся в базе
    is_archived   = models.BooleanField(default=False)
    # вложения из архива разрешает админ — по умолчанию выключено
    gallery_enabled = models.BooleanField(default=False)
    # откуда брать файлы: archive | archive2 | archive3 | t:<папка в archive-t>
    # пусто = archive2 (поведение по умолчанию)
    gallery_source  = models.CharField(max_length=300, blank=True)

    class Meta:
        ordering = ["-last_msg_at"]

    def __str__(self):
        return f"/r/{self.code} ({self.title})"

    @property
    def has_password(self):
        return bool(self.password_hash)


class ChatMessage(models.Model):
    """Сообщение в комнате: текст и/или вложение (фото/видео)."""
    MEDIA_NONE  = ""
    MEDIA_PHOTO = "photo"
    MEDIA_VIDEO = "video"

    room       = models.ForeignKey(ChatRoom, on_delete=models.CASCADE, related_name="messages")
    uid        = models.CharField(max_length=64, blank=True, db_index=True)
    name       = models.CharField(max_length=100, blank=True)
    text       = models.TextField(max_length=4000, blank=True)
    # путь файла относительно MEDIA_ROOT: "chat/<code>/<file>" для загрузок,
    # "<file>" — для ссылки на уже существующий файл архива (media_ref=True)
    media_name = models.CharField(max_length=300, blank=True)
    media_type = models.CharField(max_length=10, blank=True)
    media_ref  = models.BooleanField(default=False)
    created_at = models.DateTimeField(default=timezone.now, db_index=True)
    is_hidden  = models.BooleanField(default=False)

    class Meta:
        ordering = ["id"]

    def __str__(self):
        return f"[{self.room_id}] {self.name}: {(self.text or self.media_name)[:40]}"


class ChatVisit(models.Model):
    """Кто заходил в комнату — id гостя пишется при каждом входе."""
    room       = models.ForeignKey(ChatRoom, on_delete=models.CASCADE, related_name="visits")
    uid          = models.CharField(max_length=64, db_index=True)
    name         = models.CharField(max_length=100, blank=True)
    first_seen   = models.DateTimeField(default=timezone.now)
    last_seen    = models.DateTimeField(default=timezone.now, db_index=True)
    # id последнего прочитанного сообщения — из него считаются «галочки»
    last_read_id = models.IntegerField(default=0)

    class Meta:
        unique_together = [("room", "uid")]
        ordering = ["-last_seen"]

    def __str__(self):
        return f"{self.name or self.uid[:8]} @ {self.room.code}"


class ChatPushSub(models.Model):
    """Web Push подписка браузера на уведомления комнаты."""
    room       = models.ForeignKey(ChatRoom, on_delete=models.CASCADE,
                                   related_name="push_subs", null=True, blank=True)
    uid        = models.CharField(max_length=64, blank=True, db_index=True)
    name       = models.CharField(max_length=100, blank=True)
    endpoint   = models.TextField()
    p256dh     = models.CharField(max_length=200)
    auth       = models.CharField(max_length=100)
    created_at = models.DateTimeField(default=timezone.now)
    last_ok    = models.DateTimeField(null=True, blank=True)

    class Meta:
        unique_together = [("room", "endpoint")]

    def __str__(self):
        return f"push {self.name or self.uid[:8]} @ {self.room_id}"


class ChatShare(models.Model):
    """Публичная ссылка на слайдшоу из медиа комнаты (снимок на момент создания)."""
    token      = models.CharField(max_length=32, unique=True, db_index=True)
    room       = models.ForeignKey(ChatRoom, on_delete=models.CASCADE, related_name="shares")
    created_by = models.CharField(max_length=64, blank=True)
    title      = models.CharField(max_length=120, blank=True)
    items_json = models.TextField(default="[]")
    created_at = models.DateTimeField(default=timezone.now)
    views      = models.IntegerField(default=0)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"share {self.token} ({self.room_id})"


class MediaReaction(models.Model):
    """Один лайк одного пользователя на один файл."""
    uid = models.CharField(max_length=100, db_index=True)
    file_name = models.CharField(max_length=255, db_index=True)
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        unique_together = ("uid", "file_name")

    def __str__(self):
        return f"like {self.file_name} by {self.uid}"


class MediaComment(models.Model):
    uid = models.CharField(max_length=100, db_index=True)
    name = models.CharField(max_length=100, blank=True)
    file_name = models.CharField(max_length=255, db_index=True)
    text = models.TextField()
    created_at = models.DateTimeField(default=timezone.now, db_index=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self):
        return f"comment {self.file_name} by {self.name or self.uid}"
