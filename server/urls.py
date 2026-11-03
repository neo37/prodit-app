from django.urls import path

from .urls_chat import chat_patterns
from .views.treasures_view import (
    treasures_page, api_treasures_list,
    api_treasure_create, api_treasure_claim, api_treasure_nearby,
)
from .views.archive import archive, archive2, archive3, share_media, unlock, delete_file, move_media, api_media_list
from .views.archive_t import (
    archive_t, share_t, archive_tt, archive_tt_api,
    archive_tt_action, archive_tt_upload,
    archive_t_convert, archive_t_convert_status,
    archive_t_convert_skip, archive_t_convert_clear,
    archive_t_mkdir, archive_t_delete, archive_t_move, archive_t_slides,
)
from .views.map_view import map_page, post_detail
from .views.quests_view import quests_list, quest_detail, experience_landing
from .views.challenges_view import (
    challenges_page, api_challenge_create, api_challenge_submit,
    api_my_points, api_leaderboard,
)
from .views.board_view import board_page, board_post_detail, board_create
from .views.messages_view import (
    comments_list, comment_create, dm_send, dm_threads, dm_thread,
)
from .views.reactions import media_like, media_comment, media_comments, media_stats
from .views.pwa import manifest
from .views.qr_view import qr_capture, qr_date_capture
from .views.club_view import club_board_page, club_create
from .views.profile_view import (
    profile_page, api_profile_update, api_profile_full, api_tg_link_token,
)
from .views.admin_view import (
    admin_panel, admin_posting_page, admin_system_page, admin_delete_post,
    admin_quest_create, admin_quest_delete, admin_post_quest_update,
    admin_challenge_toggle, admin_board_delete, admin_submission_moderate,
    admin_parse_now, admin_dm_thread, admin_dm_reply, admin_broadcast,
    admin_broadcast_toggle, admin_download_code,
    admin_posting_save, admin_posting_stop, admin_posting_lock, admin_channel_save,
    admin_chats_page, admin_chat_toggle, admin_chat_password, admin_chat_create,
    admin_chat_thread,
)
from .views.api import (
    api_map_posts, api_bot_info, api_quests, api_quest,
    api_quest_validate, api_post_comments, api_auth_guest,
    api_profile, api_quest_reset, api_progress_sync,
    api_challenges, service_worker, sitemap, robots_txt,
    api_auth_telegram,
)

urlpatterns = [
    # ── Чат (тот же код обслуживает домен kaig.fun) ───────────────
    *chat_patterns,

    # ── Main pages ─────────────────────────────────────────────────
    path("",                  challenges_page,   name="home"),
    path("archive",           archive,           name="archive"),
    path("archive2",          archive2,          name="archive2"),
    path("archive3",          archive3,          name="archive3"),
    path("archive-t",         archive_t,         name="archive_t"),
    path("archive-t/convert",         archive_t_convert,        name="archive_t_convert"),
    path("archive-t/convert/status",  archive_t_convert_status, name="archive_t_convert_status"),
    path("archive-t/convert/skip",    archive_t_convert_skip,   name="archive_t_convert_skip"),
    path("archive-t/convert/clear",   archive_t_convert_clear,  name="archive_t_convert_clear"),
    path("archive-t/slides",          archive_t_slides,         name="archive_t_slides"),
    path("archive-t/mkdir",           archive_t_mkdir,          name="archive_t_mkdir"),
    path("archive-t/delete",          archive_t_delete,         name="archive_t_delete"),
    path("archive-t/move",            archive_t_move,           name="archive_t_move"),
    path("share-t",           share_t,           name="share_t"),
    path("archive-tt",         archive_tt,        name="archive_tt"),
    path("archive-tt/api",     archive_tt_api,    name="archive_tt_api"),
    path("archive-tt/action",  archive_tt_action, name="archive_tt_action"),
    path("archive-tt/upload",  archive_tt_upload, name="archive_tt_upload"),
    path("share/<str:file_name>", share_media,  name="share_media"),
    path("map",               map_page,          name="map"),
    path("post/<uuid:post_id>", post_detail,     name="post_detail"),
    path("quests",            quests_list,       name="quests"),
    path("experience",        experience_landing, name="experience"),
    path("tour",              experience_landing, name="experience_tour"),
    path("quest/<str:quest_id>", quest_detail,   name="quest_detail"),
    path("challenges",        challenges_page,   name="challenges"),
    path("treasures",         treasures_page,    name="treasures"),
    path("profile",           profile_page,      name="profile"),
    path("board",             board_page,        name="board"),
    path("board/create",      board_create,      name="board_create"),
    path("board/<slug:slug>", board_post_detail, name="board_post"),

    # ── Auth / media ───────────────────────────────────────────────
    path("unlock",  unlock,       name="unlock"),
    path("delete",  delete_file,  name="delete_file"),
    path("move",    move_media,   name="move_media"),
    path("api/media_list", api_media_list, name="api_media_list"),

    # ── Custom admin ───────────────────────────────────────────────
    path("admin",                       admin_panel,              name="admin"),
    path("admin/posting",               admin_posting_page,       name="admin_posting_page"),
    path("admin/system",                admin_system_page,        name="admin_system_page"),
    path("admin/chats",                 admin_chats_page,         name="admin_chats_page"),
    path("admin/chat/create",           admin_chat_create,        name="admin_chat_create"),
    path("admin/chat/toggle",           admin_chat_toggle,        name="admin_chat_toggle"),
    path("admin/chat/password",         admin_chat_password,      name="admin_chat_password"),
    path("admin/chat/thread/<str:code>", admin_chat_thread,       name="admin_chat_thread"),
    path("admin/download_code",         admin_download_code,      name="admin_download_code"),
    path("admin/posting/save",          admin_posting_save,       name="admin_posting_save"),
    path("admin/posting/stop",          admin_posting_stop,       name="admin_posting_stop"),
    path("admin/posting/lock",          admin_posting_lock,       name="admin_posting_lock"),
    path("admin/channel/save",           admin_channel_save,       name="admin_channel_save"),
    path("admin/delete_post",           admin_delete_post,        name="admin_delete_post"),
    path("admin/quest/create",          admin_quest_create,       name="admin_quest_create"),
    path("admin/quest/delete",          admin_quest_delete,       name="admin_quest_delete"),
    path("admin/post/quest_update",     admin_post_quest_update,  name="admin_quest_update"),
    path("admin/challenge/toggle",      admin_challenge_toggle,   name="admin_challenge_toggle"),
    path("admin/board/delete",          admin_board_delete,        name="admin_board_delete"),
    path("admin/submission/moderate",   admin_submission_moderate, name="admin_submission_moderate"),
    path("admin/parse_now",             admin_parse_now,           name="admin_parse_now"),
    path("admin/dm/thread/<str:peer>",  admin_dm_thread,           name="admin_dm_thread"),
    path("admin/dm/reply",              admin_dm_reply,            name="admin_dm_reply"),
    path("admin/broadcast",             admin_broadcast,           name="admin_broadcast"),
    path("admin/broadcast_toggle",      admin_broadcast_toggle,    name="admin_broadcast_toggle"),
    # Комментарии и личные сообщения
    path("api/board/<slug:slug>/comments",       comments_list,  name="comments_list"),
    path("api/board/<slug:slug>/comment",        comment_create, name="comment_create"),
    path("api/dm/send",                          dm_send,        name="dm_send"),
    path("api/dm/threads",                       dm_threads,     name="dm_threads"),
    path("api/dm/thread/<str:peer>",             dm_thread,      name="dm_thread"),

    # ── API ────────────────────────────────────────────────────────
    path("api/map_posts",               api_map_posts,            name="api_map_posts"),
    path("api/bot_info",                api_bot_info,             name="api_bot_info"),
    path("api/quests",                  api_quests,               name="api_quests"),
    path("api/quest/validate",          api_quest_validate,       name="api_quest_validate"),
    path("api/quest/reset",             api_quest_reset,          name="api_quest_reset"),
    path("api/quest/<str:quest_id>",    api_quest,                name="api_quest"),
    path("api/post/<uuid:post_id>/comments", api_post_comments,   name="api_post_comments"),
    path("api/auth/guest",              api_auth_guest,           name="api_auth_guest"),
    path("api/auth/telegram",           api_auth_telegram,        name="api_auth_telegram"),
    path("api/profile",                 api_profile,              name="api_profile"),
    path("api/progress/sync",           api_progress_sync,        name="api_progress_sync"),
    path("api/profile/full",            api_profile_full,         name="api_profile_full"),
    path("api/profile/update",         api_profile_update,       name="api_profile_update"),
    path("api/profile/tg_link",        api_tg_link_token,        name="api_tg_link"),
    path("api/challenges",              api_challenges,           name="api_challenges"),
    path("api/challenges/create",       api_challenge_create,     name="api_challenges_create"),
    path("api/challenges/<str:challenge_id>/submit", api_challenge_submit, name="api_challenge_submit"),
    path("api/treasures",                 api_treasures_list,       name="api_treasures"),
    path("api/treasures/create",          api_treasure_create,      name="api_treasure_create"),
    path("api/treasures/nearby",          api_treasure_nearby,      name="api_treasure_nearby"),
    path("api/treasures/<uuid:treasure_id>/claim", api_treasure_claim, name="api_treasure_claim"),
    path("api/my_points",               api_my_points,            name="api_my_points"),
    path("api/leaderboard",             api_leaderboard,          name="api_leaderboard"),

    # ── QR capture ────────────────────────────────
    path("qr/date/<str:code>", qr_date_capture, name="qr_date_capture"),
    path("qr/<str:code>", qr_capture, name="qr_capture"),

    # ── Секретные доски клуба (без ссылки с главной) ──
    path("date/<str:code>/create", club_create, name="club_create"),
    path("date/<str:code>", club_board_page, name="club_board"),

    # ── Лайки и комментарии к медиа ────────────────────────────────
    path("api/media/like",     media_like,     name="media_like"),
    path("api/media/comment",  media_comment,  name="media_comment"),
    path("api/media/comments", media_comments, name="media_comments"),
    path("api/media/stats",    media_stats,    name="media_stats"),

    # ── Service files ──────────────────────────────────────────────
    path("sw.js",           service_worker, name="sw"),
    path("manifest.json",   manifest,       name="manifest"),
    path("sitemap.xml",     sitemap,        name="sitemap"),
    path("robots.txt",      robots_txt,     name="robots"),
]
