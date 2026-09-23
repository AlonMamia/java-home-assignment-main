<div dir="rtl">

# DECISIONS

## 1. החלטות ארכיטקטוניות
- **שכבות:** `Controller → Service → Repository`. ה-controller (`LeaveRequestsController`) הוא **adapter דק ל-HTTP בלבד**: הוא ממפה בקשה ל-DTO/פרמטרים, קורא למתודה מתאימה ב-`LeaveRequestService`, ומחזיר את ה-`ResponseEntity` שהתקבל — בלי לוגיקה עסקית ובלי שום `try/catch` משלו. כל הלוגיקה העסקית (בדיקות תקינות, חישוב ימים, בדיקת מכסה, מעברי סטטוס) נמצאת אך ורק ב-`LeaveRequestService`. **מיפוי חריגות ל-HTTP status מפוצל בכוונה בין שתי גישות** (ראו 3.1 ו-3.2): ב-`create()` הוא עדיין קורה בתוך ה-service עצמו; ב-`approve()` הוא הוצא ל-`GlobalExceptionHandler` (`@RestControllerAdvice`) ייעודי, בעקבות באג rollback שתוקן ב-3.2. אי-האחידות הזו מתועדת שם במפורש, כולל למה.
- **חריגות מותאמות במקום קודי שגיאה גולמיים:** נוצרו חריגות עסקיות ייעודיות תחת `com.example.leavemanagement.exception` (`EmployeeNotFoundException`, `LeaveRequestNotFoundException`, `InvalidLeaveRequestStateException`, `InsufficientVacationBalanceException`) במקום `return ResponseEntity.status(404)...` מפוזר בקוד. זה נותן שמות משמעותיים לכל מסלול כשל, ומאפשר לרכז את ההחלטה "איזה קוד HTTP מתאים לאיזו שגיאה עסקית" במקום אחד — גם אם ה"מקום האחד" הזה שונה בין `create()` ל-`approve()` (ראו למעלה).
- **`GET`-ים (`getAll`, `search`) נשארו כפי שהם:** אלה endpoints לקריאה בלבד, ללא לוגיקה עסקית או טיפול בחריגות — הזזתם לשכבת service לא הייתה תורמת להפרדת אחריות ותוספת השכבה שם הייתה over-engineering ביחס למה שהם עושים בפועל (query ישיר). שימו לב ש-`search` עדיין בונה שאילתת SQL native עם concatenation של קלט המשתמש — זו בעיית **SQL Injection** קיימת שלא תוקנה כחלק מהמשימות הללו (ראו סעיף "אבטחה" למטה אם תוקן בהמשך).

## 2. הבאג ביתרת החופשה
- **מה היה הבאג, איפה:** ב-`LeaveRequestsController.create()` (`backend/src/main/java/.../controller/LeaveRequestsController.java`), הקוד חישב `used` — סך ימי החופשה שכבר אושרו לעובד — אבל **מעולם לא השתמש בו** בבדיקת התקנה. התנאי היה `days > employee.getAnnualQuota()`, כלומר הוא בדק רק אם *הבקשה החדשה עצמה* חורגת מהמכסה השנתית המלאה, ולא לקח בחשבון ימים שכבר נוצלו. עובד עם מכסה של 10 ימים שכבר ניצל 8 יכול היה להגיש בקשה נוספת ל-5 ימים ולקבל אישור (200 OK), למרות שהסך המצטבר (13) חורג מהמכסה.
- **איך תיקנתי:** שיניתי את התנאי ל-`used + days > employee.getAnnualQuota()` — כך שהבדיקה משווה את **היתרה הנותרת** (מכסה פחות ימים שכבר אושרו) מול אורך הבקשה החדשה.
- **הטסט שמוכיח את התיקון:** `LeaveRequestsTests.create_ExceedingRemainingBalance_IsRejected` — יוצר עובד עם מכסה 10, בקשה מאושרת קיימת של 8 ימים, ומנסה להגיש בקשה נוספת ל-5 ימים (8+5=13 > 10). וידאתי ידנית שהטסט **נכשל** מול הקוד המקורי (200 OK במקום 400) ועובר לאחר התיקון. הוספתי גם `create_ExactlyAtRemainingBalance_Succeeds` לבדיקת מקרה הגבול (8+2=10 בדיוק) — כדי לוודא שהתיקון לא "חונק" בקשות תקינות.

## 3. אישור בקשה (approve) ו‑concurrency

### מה נבנה
- `POST /api/leave-requests/{id}/approve` — endpoint חדש שמאשר בקשת חופשה.
- לוגיקה עסקית הועברה ל-`LeaveRequestService.approve()` חדש (ולא נשארה בקונטרולר), כדי שאפשר יהיה לעטוף אותה ב-`@Transactional` אחד קוהרנטי (טעינה + נעילה + בדיקה + עדכון), ולא לגרור ניהול טרנזקציות לתוך ה-controller. בשלב הזה זה היה שינוי ממוקד רק ל-endpoint החדש (`create()` נשאר זמנית ב-controller). **בהמשך, לפי הנחיה מפורשת, גם `create()` הועבר ל-service וגם מיפוי החריגות ל-HTTP status הוזז מה-controller פנימה ל-service** — ראו סעיף 3.1 לפירוט המלא של הרפקטור הזה וההנחיה שמאחוריו.

### איך טיפלתי במצבים לא חוקיים
- **בקשה לא קיימת:** `LeaveRequestService.approve()` זורק `LeaveRequestNotFoundException`, שה-controller תופס ומחזיר **404**.
- **בקשה שכבר אושרה/נדחתה:** נזרק `InvalidLeaveRequestStateException`, ומוחזר **409 Conflict** (בחרתי 409 ולא 400 כי זה בדיוק המקרה הקלאסי שה-status הזה נועד לו — ניסיון לבצע פעולה חוקית-כשלעצמה על משאב שנמצא כרגע במצב שלא מאפשר אותה, לא קלט שגוי).
- **חריגה מהמכסה בזמן האישור:** נזרק `InsufficientVacationBalanceException`, ומוחזר **400 Bad Request** — עקבי עם ההודעה הקיימת ב-`create()` ("Not enough vacation balance").

### הבעיה: race condition בין שתי בקשות ממתינות
התרחיש שהמשימה מצביעה עליו הוא לא רק "double click" על אותה בקשה — הוא **שתי בקשות PENDING שונות** לאותו עובד, שכל אחת בנפרד נמצאת בתוך היתרה (כי הבדיקה ב-`create()` סופרת רק ימים **מאושרים**), אבל אישור של שתיהן ביחד יחרוג מהמכסה. לדוגמה: מכסה 5, שתי בקשות ממתינות בנות 3 ימים כל אחת — כל אחת לבד תקינה (0+3≤5), אבל אם שתיהן מאושרות "במקביל" התוצאה היא 6>5.

בלי נעילה, שני thread-ים (או שני ניהולי/admin שלוחצים כמעט בו-זמנית) יכולים:
1. שניהם לקרוא `used = 0` (אף בקשה עוד לא אושרה).
2. שניהם לעבור את הבדיקה `0 + 3 ≤ 5`.
3. שניהם לשמור `status = APPROVED`.

התוצאה: 6 ימים מאושרים מתוך מכסה של 5. קלאסי **check-then-act race condition**.

### הפתרון שנבחר: Pessimistic Locking (`SELECT ... FOR UPDATE`)
בחרתי בנעילה פסימית על פני optimistic locking (`@Version`), מהסיבות הבאות:
- הפעולה (`approve`) היא ניהולית ונדירה יחסית (לא path בעומס גבוה כמו קריאה), כך שהעלות של נעילה (contention) זניחה, ואין צורך במנגנון retry שנדרש ב-optimistic locking כשיש קונפליקט.
- הלוגיקה כאן היא "read-check-then-write" שמשתרעת על **שתי טבלאות קשורות** (`leave_requests` ו-`employees`) — קל יותר להבטיח נכונות עם נעילה מפורשת שמונעת מ-transaction שני אפילו *לקרוא* את הנתונים עד שהראשון סיים, מאשר לזהות קונפליקט אחרי מעשה ולהתמודד עם retry-loop שצריך לחזור על כל השרשרת (fetch leave request → fetch employee → recompute used → recheck).
- PostgreSQL (שכבר קיים בפרויקט) תומך ב-`SELECT ... FOR UPDATE` באופן טבעי דרך Hibernate/Spring Data (`@Lock(LockModeType.PESSIMISTIC_WRITE)`), ולא דרש שינוי סכימה (אין צורך בעמודת `version`).

**סדר הנעילות ב-`LeaveRequestService.approve()`:**
1. `leaveRequestRepository.findByIdForUpdate(id)` — נועל את שורת ה-`leave_requests` (`SELECT ... FOR UPDATE`). זה מטפל גם במקרה של אישור כפול של **אותה בקשה** בדיוק (double-click): ה-transaction השני נחסם עד שהראשון מסיים, ואז קורא את הסטטוס המעודכן (`APPROVED`) ונדחה עם 409.
2. `employeeRepository.findByIdForUpdate(employeeId)` — נועל את שורת ה-`employees` **לאחר** נעילת הבקשה. זה מסדר (serializes) כל שני אישורים במקביל של בקשות **שונות** לאותו עובד — ה-transaction השני ימתין עד שהראשון יסיים (commit/rollback) ורק אז יחשב מחדש את `used` ויבדוק את המכסה מול המצב המעודכן.
3. חישוב `used` (סכום ימים מאושרים) והשוואה למכסה מתבצעים **בתוך** אותו transaction, אחרי שתי הנעילות — כך שאין חלון זמן שבו transaction אחר יכול "להתערב".

**סדר קבוע ואחיד** (תמיד leave-request לפני employee) מונע deadlock בין שני threads שכל אחד מנסה לנעול את אותם שני משאבים בסדר הפוך.

הנעילות משתחררות יחד עם סגירת ה-transaction (`commit`/`rollback`), ולכן כל הלוגיקה חייבת לרוץ בתוך `@Transactional` יחיד (ולא, למשל, ב-controller עם קריאות repository נפרדות ללא טרנזקציה משותפת — זה היה משחרר את הנעילה מיד אחרי כל query ולא מגן על כלום).

### Trade-offs וחלופות שנשקלו
- **Optimistic locking (`@Version` על `Employee`):** יעיל יותר בעומס גבוה (ללא locks תופסי-משאב), אבל דורש retry-loop בצד השרת (או שגיאת 409 שהלקוח צריך לנסות שוב), ופחות טבעי כאן כי אנחנו רוצים תשובה סופית ומיידית ("אושר" / "נדחה") ולא "נסה שוב". שקלתי לשלב את שתי הגישות (optimistic על `Employee.version` + retry) אך זה over-engineering ביחס לתדירות הפעולה.
- **נעילה ברמת עובד בלבד (בלי לנעול את הבקשה עצמה):** הייתה פותרת את בעיית שתי-בקשות-שונות, אבל לא הייתה מגנה במלואה מפני אישור כפול מדויק של אותה בקשה בדיוק תחת timing מסוים (תלוי בהתנהגות ה-cache ברמת ה-persistence context) — לכן נעלתי את שתיהן, בעלות זניחה.
- **מה הייתי עושה עם עוד זמן:** להוסיף מנגנון idempotency-key ל-endpoint (כדי שלקוח שמנסה שוב אחרי timeout לא ייצור אפקט כפול), ולשקול להעביר גם את `create()` לאותו `LeaveRequestService` לצורך אחידות (משימה 3).

### הטסטים
כל הטסטים ב-`backend/src/test/java/.../LeaveRequestApprovalTests.java`, מריצים מול PostgreSQL אמיתי (Testcontainers):
- `approve_PendingRequest_SucceedsAndUpdatesStatus` — אישור תקין מעדכן סטטוס ל-APPROVED.
- `approve_AlreadyApprovedRequest_ReturnsConflict` / `approve_RejectedRequest_ReturnsConflict` — 409 על מעבר מצב לא חוקי.
- `approve_NonExistentId_ReturnsNotFound` — 404 על מזהה לא קיים.
- `approve_ExceedingRemainingBalance_ReturnsBadRequest` — 400 כשהאישור עצמו (לא רק היצירה) יחרוג מהמכסה.
- `approve_ConcurrentApprovalsExceedingCombinedQuota_OnlyOneSucceeds` — **טסט ה-concurrency**: עובד עם מכסה 5, שתי בקשות ממתינות בנות 3 ימים. שני threads קוראים ל-`approve()` בו-זמנית (מסונכרנים עם `CountDownLatch` כדי למקסם חפיפה), ומוודא שבדיוק אחת מצליחה והשנייה נדחית עם 400, וכי סך הימים המאושרים לעולם לא חורג מהמכסה.
  **וידוא שהטסט אכן תופס את הבעיה:** הסרתי זמנית את הנעילות (`findByIdForUpdate` → `findById` רגיל) והוספתי delay מלאכותי, והרצתי את הטסט מחדש — הוא נכשל (שתי הבקשות אושרו, 6 ימים מתוך מכסה 5), מה שמוכיח שהטסט אכן בודק את מה שהוא אמור לבדוק. לאחר שהחזרתי את הנעילות, הטסט עובר בעקביות.

### 3.1 רפקטור: כל הלוגיקה העסקית וטיפול החריגות עברו לשכבת ה-Service בלבד

> **הערה מפורשת:** הרפקטור הארכיטקטוני הספציפי הזה — אכיפה מחמירה של "לוגיקה עסקית וטיפול חריגות רק ב-service, ה-controller אחראי אך ורק ל-HTTP" — **לא היה ההחלטה המקורית שלי (ה-AI). הוא בוצע לפי הנחיה מפורשת ומכוונת של הסוקר/המשתמש**, לאחר שבגרסה הקודמת ה-controller עדיין הכיל בלוקים של `try/catch` שממפים את החריגות שנזרקות מ-`LeaveRequestService.approve()` לקודי HTTP (`404` / `409` / `400`). ההנחיה הייתה להזיז גם את המיפוי הזה עצמו פנימה ל-service, כך שלקונטרולר לא יישאר שום `catch` ושום ידיעה על סוגי החריגות העסקיות.

**מה היה לפני:**
```java
// LeaveRequestsController (לפני) — ה-controller מכיל גם לוגיקה עסקית (ב-create)
// וגם מיפוי חריגות ל-HTTP status (ב-approve):
@PostMapping
public ResponseEntity<?> create(@RequestBody CreateLeaveRequestDto dto) {
    Employee employee = employeeRepository.findById(dto.getEmployeeId()).orElse(null);
    if (employee == null) { return ResponseEntity.status(404).body("Employee not found"); }
    // ... חישוב ימים, בדיקת מכסה, שמירה — הכול כאן ...
}

@PostMapping("/{id}/approve")
public ResponseEntity<?> approve(@PathVariable Long id) {
    try {
        LeaveRequest approved = leaveRequestService.approve(id);
        return ResponseEntity.ok(approved);
    } catch (LeaveRequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    } catch (InvalidLeaveRequestStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    } catch (InsufficientVacationBalanceException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
```

**מה יש עכשיו:**
```java
// LeaveRequestsController (עכשיו) — אין לוגיקה עסקית, אין catch, רק העברת בקשה/תשובה:
@PostMapping
public ResponseEntity<?> create(@RequestBody CreateLeaveRequestDto dto) {
    return leaveRequestService.create(dto);
}

@PostMapping("/{id}/approve")
public ResponseEntity<?> approve(@PathVariable Long id) {
    return leaveRequestService.approve(id);
}
```

```java
// LeaveRequestService (עכשיו) — כל מתודה ציבורית עוטפת מתודה פרטית שזורקת חריגות
// עסקיות, ותופסת אותן בעצמה כדי לבנות את ה-ResponseEntity הנכון:
public ResponseEntity<?> create(CreateLeaveRequestDto dto) {
    try {
        return ResponseEntity.ok(createInternal(dto));
    } catch (EmployeeNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    } catch (InsufficientVacationBalanceException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
private LeaveRequest createInternal(CreateLeaveRequestDto dto) { /* הלוגיקה העסקית המלאה, זורקת חריגות */ }

@Transactional
public ResponseEntity<?> approve(Long leaveRequestId) {
    try {
        return ResponseEntity.ok(approveInternal(leaveRequestId));
    } catch (LeaveRequestNotFoundException | EmployeeNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    } catch (InvalidLeaveRequestStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    } catch (InsufficientVacationBalanceException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
private LeaveRequest approveInternal(Long leaveRequestId) { /* הנעילות + הלוגיקה העסקית, זורקת חריגות */ }
```

**נקודות מפתח בעיצוב:**
- כל מתודה ציבורית ב-service (`create`, `approve`) מחזירה `ResponseEntity<?>` ישירות, ועוטפת מתודה פרטית (`createInternal`, `approveInternal`) שמכילה את הלוגיקה העסקית הטהורה וזורקת חריגות מותאמות. ה-`try/catch` שממפה חריגה → קוד HTTP חי עכשיו **רק** בתוך ה-service.
- `@Transactional` נשאר על המתודה הציבורית `approve()` (לא על ה-private) — כי Spring AOP מיירט רק קריאות שמגיעות מבחוץ ל-bean (proxy), ולכן חובה שהגבול הטרנזקציוני (וכפועל יוצא, הנעילות הפסימיות שתוארו בסעיף 3) יעטוף גם את הבלוק שתופס את החריגות, אחרת הנעילה הייתה משתחררת לפני שהתשובה נבנית.
- `LeaveRequestsController` כבר לא מזריק (inject) את `EmployeeRepository` בכלל — היא לא בשימוש שם יותר, כי כל הגישה לעובדים עברה ל-service.

**Trade-off שאני מציין במפורש (ולמה בכל זאת יישמתי כך):** הגישה הזו הופכת את `LeaveRequestService` לתלוי בטיפוסי Spring Web (`ResponseEntity`, `HttpStatus`), מה שסוטה מהפרדת-שכבות "טהורה" קלאסית שבה ה-service אמור להחזיר אובייקטים עסקיים בלבד (או לזרוק חריגות שהקונטרולר תופס, למשל דרך `@ControllerAdvice` גלובלי), בלי לדעת כלל שהוא "מדבר HTTP". חלופה כזו (`@RestControllerAdvice` ייעודי שממפה את אותן החריגות לתשובות) הייתה שומרת על ה-service "נקי" מ-HTTP לגמרי, וזו הגישה שהייתי בוחר כברירת מחדל. אך מכיוון שקיבלתי הנחיה מפורשת וממוקדת ("Move all catch blocks and exception handling ... to the Service Layer" — לא ל-`@ControllerAdvice`), יישמתי בדיוק לפי ההנחיה, ומתעד כאן את הפשרה כדי שתהיה שקופה. **עדכון:** בדיוק ה-trade-off הזה חזר להטריד בפועל — ראו סעיף 3.2, שם הגישה הזו גרמה לבאג אמיתי בטיפול בטרנזקציה של `approve()`, ותוקנה בחזרה בדיוק לכיוון ה-`@RestControllerAdvice` שמתואר כאן כברירת המחדל המועדפת (עבור `approve()` בלבד; `create()` נשאר כפי שהוא).

**טסטים שעודכנו:** `LeaveRequestsTests` ו-`LeaveRequestApprovalTests` הוזזו מקריאה ל-`LeaveRequestsController` לקריאה ישירה ל-`LeaveRequestService` (`leaveRequestService.create(...)` / `leaveRequestService.approve(...)`), כי שם נמצאת עכשיו כל הלוגיקה שנבדקת — קריאה דרך ה-controller הדק לא הייתה מוסיפה כיסוי מעבר למה שהיה נבדק ישירות מול ה-service. נוסף גם טסט חדש, `create_NonExistentEmployee_ReturnsNotFound`, לכיסוי מסלול ה-404 של `create()` שהפך עכשיו לחריגה (`EmployeeNotFoundException`) במקום בדיקת `null` inline — כדי לוודא שההתנהגות החיצונית לא השתנתה תוך כדי הרפקטור. כל 10 הטסטים (4 ב-`LeaveRequestsTests`, 6 ב-`LeaveRequestApprovalTests`) עוברים מול PostgreSQL אמיתי (Testcontainers) לאחר הרפקטור.

### 3.2 תיקון: bug בטיפול בטרנזקציה של `approve()` — חריגות עסקיות חוזרות לזרום החוצה, מיפוי ל-HTTP עבר ל-`@RestControllerAdvice`

> **הערה מפורשת:** התיקון הארכיטקטוני הזה **גם הוא בוצע לפי בקשה מפורשת שלי (המשתמש/הסוקר)**, ולא כיוזמת AI עצמאית. הוא **חוזר בו חלקית** מהחלטת 3.1 — שם הנחיתי במפורש להעביר את מיפוי החריגות ל-HTTP status *לתוך* ה-service; כאן ביקשתי להוציא את המיפוי הזה *מ*-`approve()` בחזרה, ולרכז אותו ב-`@RestControllerAdvice` ייעודי. הסיבה לא הייתה העדפת סגנון, אלא **באג אמיתי ומתועד בטיפול בטרנזקציה** שהתגלה ב-3.1, כמפורט למטה. `create()` לא נגע — הבאג הספציפי הזה שייך רק למתודות `@Transactional`, ו-`create()` אינה כזו.

**הבאג שהתגלה:** ב-3.1, `LeaveRequestService.approve()` היה מסומן `@Transactional`, אבל תפס בעצמו (`try/catch`) את כל החריגות העסקיות שנזרקות מ-`approveInternal()` כדי לבנות `ResponseEntity` בעצמו. הבעיה: **Spring מבצע rollback אוטומטי רק כשחריגה unchecked בורחת (escape) מהמתודה המסומנת `@Transactional`**. מכיוון שהחריגות נתפסו *בתוך* אותה מתודה ומעולם לא ברחו ממנה, ה-transaction interceptor של Spring **לא ידע להפעיל rollback בגללן** — הוא ראה מתודה שהסתיימה בהצלחה (החזירה `ResponseEntity`, לא זרקה כלום), וביצע `commit` רגיל.

**האם זה גרם לבאג בפועל?** לא — נכון לגרסה הנוכחית, כל הבדיקות העסקיות (בקשה לא קיימת / כבר מאושרת / חריגה ממכסה) נזרקות **לפני** ה-write היחיד (`request.setStatus(APPROVED); leaveRequestRepository.save(request)`), כך שבמסלולי כישלון אין בכלל שינוי לבטל. אבל המבנה **שביר**: אם מישהו בעתיד יוסיף פעולת כתיבה נוספת (למשל, רישום audit log, עדכון שדה ביניים) **לפני** אחת הבדיקות הקיימות, אותה כתיבה תתבצע ותתחייב (`commit`) גם כשהבקשה נדחית עם שגיאה — כי אין מנגנון rollback אמיתי שמגן על הרצף, רק מזל שכרגע אין כתיבה מוקדמת. זה בדיוק סוג הבאג ש"עובד היום, מתפוצץ מחר" — ולכן תוקן מיד כשזוהה, לפני שנוסף קוד נוסף שסומך בטעות על ההגנה הטרנזקציונית שלא הייתה קיימת.

**התיקון:**
- `approve()` כבר לא תופס את החריגות שהוא זורק. הוסרה החלוקה `approve()`/`approveInternal()` — כל הלוגיקה (כולל שתי הנעילות, בדיקת הסטטוס, וחישוב/בדיקת המכסה, **באותו סדר כמו קודם**) חיה עכשיו במתודה הציבורית היחידה `approve()`, שמחזירה `LeaveRequest` (לא `ResponseEntity`) וזורקת את החריגות **החוצה**. עכשיו חריגה unchecked שבורחת מ-`approve()` גורמת ל-Spring לבצע rollback אמיתי על כל מה שנעשה בתוך אותו transaction (כולל שחרור הנעילות הפסימיות) — בדיוק ההתנהגות שרצינו מהתחלה.
- נוסף `GlobalExceptionHandler` חדש (`com.example.leavemanagement.exception.GlobalExceptionHandler`, מסומן `@RestControllerAdvice`) שממפה את אותן החריגות לאותם קודי HTTP כמו קודם: `LeaveRequestNotFoundException`/`EmployeeNotFoundException` → **404**, `InvalidLeaveRequestStateException` → **409**, `InsufficientVacationBalanceException` → **400**. שום קוד סטטוס לא השתנה — רק *המקום* שבו ההחלטה מתקבלת.
- `LeaveRequestsController.approve()` השתנה מ-`ResponseEntity<?> approve(...) { return leaveRequestService.approve(id); }` ל:
  ```java
  @PostMapping("/{id}/approve")
  public ResponseEntity<LeaveRequest> approve(@PathVariable Long id) {
      return ResponseEntity.ok(leaveRequestService.approve(id));
  }
  ```
  הוא עדיין לא תופס שום חריגה בעצמו — כשהיא נזרקת, היא פשוט ממשיכה לברוח דרכו, ו-Spring MVC מפנה אותה ל-`GlobalExceptionHandler`. ה-controller נשאר "מטומטם" (dumb) לגמרי לגבי HTTP status של כשל — הוא רק בונה את תשובת ההצלחה.
- `create()` **לא שונה** ונשאר עם ה-`try/catch` המקומי שלו כפי שתואר ב-3.1 (הוא לא `@Transactional`, אז הבאג הספציפי הזה לא חל עליו). זו אי-אחידות מודעת: `approve()` משתמש ב-advice הגלובלי, `create()` עדיין ממפה לבד. עם עוד זמן הייתי מאחד את שתי המתודות לאותה גישה (כנראה: להעביר גם את `create()` דרך ה-advice, כדי שלא יהיו שני מנגנוני מיפוי-חריגות מקבילים לאותן חריגות בדיוק).

**טסטים שעודכנו:**
- `LeaveRequestApprovalTests` — הותאם לכך ש-`leaveRequestService.approve(...)` זורק חריגה במקום להחזיר `ResponseEntity`: מסלולי הכישלון עכשיו נבדקים עם `assertThrows(...)` על סוג החריגה הספציפי, במקום בדיקת `getStatusCode()`. גם טסט ה-concurrency הותאם: כל thread מריץ `Callable<LeaveRequest>`, וה-thread שנדחה נתפס דרך `ExecutionException` שעוטפת את `InsufficientVacationBalanceException`.
- נוסף קובץ טסט חדש, `LeaveRequestsControllerApprovalHttpTests` (עם `@AutoConfigureMockMvc`), שמריץ בקשות HTTP אמיתיות מול ה-endpoint (`mockMvc.perform(post(...))`) ובודק את קודי הסטטוס (`200`/`404`/`409`/`400`) **מקצה לקצה, דרך ה-`DispatcherServlet` וה-advice בפועל** — כי מאז השינוי, בדיקה ברמת ה-service בלבד כבר לא יכולה להוכיח שקוד ה-HTTP הנכון מוחזר (ה-service כבר לא מחזיר `ResponseEntity`). זה הכיסוי שמוכיח ש"התנהגות קודי הסטטוס נשמרה" כפי שהתבקש.
- כל 14 הטסטים (4 ב-`LeaveRequestsTests`, 6 ב-`LeaveRequestApprovalTests`, 4 ב-`LeaveRequestsControllerApprovalHttpTests`) עוברים מול PostgreSQL אמיתי (Testcontainers).

## 4. על מה ויתרתי בגלל הזמן
- ... ומה הייתי עושה עם עוד יום:

## 5. שימוש ב‑AI
### איפה AI עזר (כולל prompts)
1. prompt: "..." → מה קיבלתי ומה עשיתי איתו:
2. ...

### איפה דחיתי/תיקנתי הצעה של AI
- מה AI הציע, למה זה היה שגוי, ומה עשיתי במקום:

### אבטחה
- אם מצאתם בעיית אבטחה: מה מצאתם, איפה (קובץ + שורה), למה זו בעיה, ואיך תיקנתם:

## 6. הוראות הרצה
- (אם שיניתם משהו מהוראות ה‑README המקורי)

</div>
