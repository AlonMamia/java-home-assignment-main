<div dir="rtl">

# DECISIONS

## 1. החלטות ארכיטקטוניות
- **שכבות:** `Controller → Service → Repository`. ה-controller (`LeaveRequestsController`) הוא **adapter דק ל-HTTP בלבד**: הוא ממפה בקשה ל-DTO/פרמטרים, קורא למתודה מתאימה ב-`LeaveRequestService`, ומחזיר את ה-`ResponseEntity` שהתקבל — בלי לוגיקה עסקית ובלי שום `try/catch` משלו. כל הלוגיקה העסקית (בדיקות תקינות, חישוב ימים, בדיקת מכסה, מעברי סטטוס) נמצאת אך ורק ב-`LeaveRequestService`. **מיפוי חריגות ל-HTTP status מפוצל בכוונה בין שתי גישות** (ראו 3.1 ו-3.2): ב-`create()` הוא עדיין קורה בתוך ה-service עצמו; ב-`approve()` הוא הוצא ל-`GlobalExceptionHandler` (`@RestControllerAdvice`) ייעודי, בעקבות באג rollback שתוקן ב-3.2. אי-האחידות הזו מתועדת שם במפורש, כולל למה.
- **חריגות מותאמות במקום קודי שגיאה גולמיים:** נוצרו חריגות עסקיות ייעודיות תחת `com.example.leavemanagement.exception` (`EmployeeNotFoundException`, `LeaveRequestNotFoundException`, `InvalidLeaveRequestStateException`, `InsufficientVacationBalanceException`) במקום `return ResponseEntity.status(404)...` מפוזר בקוד. זה נותן שמות משמעותיים לכל מסלול כשל, ומאפשר לרכז את ההחלטה "איזה קוד HTTP מתאים לאיזו שגיאה עסקית" במקום אחד — גם אם ה"מקום האחד" הזה שונה בין `create()` ל-`approve()` (ראו למעלה).
- **`GET`-ים (`getAll`, `search`) נשארו כפי שהם:** אלה endpoints לקריאה בלבד, ללא לוגיקה עסקית או טיפול בחריגות — הזזתם לשכבת service לא הייתה תורמת להפרדת אחריות ותוספת השכבה שם הייתה over-engineering ביחס למה שהם עושים בפועל (query ישיר). שימו לב ש-`search` עדיין בונה שאילתת SQL native עם concatenation של קלט המשתמש — זו בעיית **SQL Injection** קיימת שלא תוקנה כחלק מהמשימות הללו (ראו סעיף "אבטחה" למטה אם תוקן בהמשך).
  **עדכון (סעיף 3.3):** כשהתבקש רפקטור מלא ל-3-tier architecture, ההערכה הזו התהפכה — גם `getAll`/`search` הועברו לעבור דרך `LeaveRequestService` (מתודות דקות, ללא לוגיקה עסקית "אמיתית", רק כדי ששום קוד ב-controller לא ייגע ב-repository/EntityManager ישירות), ובעיית ה-SQL Injection תוקנה בפועל. ראו 3.3 ו"אבטחה" למטה לפרטים המלאים.

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

### 3.3 רפקטור מסכם: אכיפת 3-Tier Architecture מלאה (Controller thin / Service owns הכל / Repository בלבד ל-DB access)

> **הערה מפורשת:** גם הרפקטור הזה בוצע לפי הנחיה מפורשת של הסוקר/המשתמש ("Controller currently handles everything — direct database access, business logic, error handling, and request validation. Refactor to strictly adhere to standard 3-Tier Architecture"). הוא משלים ישירות את מה שתועד כ"נשאר בצריך תיקון" בסעיפים 1 ו-3.2 למעלה: אי-האחידות `create()` מול `approve()`, וה-SQL Injection ב-`search()` שצוין במפורש כלא-מטופל.

**מה היה לא תקין לפני הרפקטור הזה, ולמה:**
1. **`LeaveRequestsController.search()` ניגש ל-`EntityManager` ישירות מה-controller ובנה שאילתת SQL native עם string concatenation של קלט המשתמש** — גם הפרת שכבות (data access בתוך ה-controller, לא ב-repository) וגם **פרצת SQL Injection** ממשית (למשל `name=' OR '1'='1` היה חושף את כל השורות, ותחביר עם `--`/`;` יכול לשנות את השאילתה לגמרי).
2. **`LeaveRequestsController.getAll()` ניגש ל-`LeaveRequestRepository` ישירות** וגם מיין את התוצאה (`.sorted(...)`) בתוך ה-controller — לוגיקת מיון היא כלל עסקי-תצוגתי קטן שצריך לחיות ב-service/repository, לא בשכבת ה-HTTP.
3. **`EmployeesController` הזריק (`inject`) את `EmployeeRepository` ישירות**, ללא שכבת service בכלל — היחיד מבין שני ה-controllers שלא עבר דרך service, מה שיצר חוסר-עקביות בין שני האזורים של אותו קוד בסיס.
4. **`LeaveRequestService.create()` עדיין תפס (`try/catch`) חריגות עסקיות ובנה `ResponseEntity` בעצמו**, בזמן ש-`approve()` כבר זורק ומאציל את המיפוי ל-`GlobalExceptionHandler` (ראו 3.1–3.2) — בדיוק אי-האחידות שסומנה שם כ"מה הייתי עושה עם עוד זמן — לאחד את שתי הגישות". `create()` גם אינה `@Transactional`, כך שאין כאן את הסכנה הספציפית שתוארה ב-3.2, אבל ה-`try/catch` בתוך ה-service עדיין סותר את העיקרון "Controller אחראי בלעדית לקודי HTTP".
5. **לא הייתה שום ולידציה על ה-payload הנכנס** (`CreateLeaveRequestDto`) — שדה `null` (למשל `employeeId` חסר) היה עובר עד עומק הלוגיקה העסקית וגורם ל-`NullPointerException` (500 Internal Server Error) במקום 400 ברור על קלט לא תקין.

**מה השתנה, שכבה-שכבה:**

- **Repository (`LeaveRequestRepository`):** נוספו שתי מתודות derived-query במקום גישה ידנית:
  - `findByEmployee_NameContainingIgnoreCase(String name)` — מחליפה את ה-native SQL המסוכן ב-`search()`; Spring Data מייצר `JOIN` + `LIKE` פרמטרי (bound parameter, לא concatenation) על סמך קשר ה-`@ManyToOne` הקיים בין `LeaveRequest.employee` ל-`Employee`. סוגר לגמרי את פרצת ה-SQL Injection.
  - `findAllByOrderByStartDateDesc()` — מחליפה את המיון הידני (`.stream().sorted(...)`) ב-`getAll()`; המיון עכשיו קורה ב-DB (`ORDER BY`), לא באפליקציה.
  - `EmployeeRepository` נשאר ללא שינוי — כבר היה נקי מכל דבר מלבד JPA.

- **Service:**
  - `LeaveRequestService` קיבל שתי מתודות ציבוריות חדשות, `getAll()` ו-`search(name)`, שכל אחת רק מאצילה ל-repository — אין כאן לוגיקה עסקית "אמיתית" (זה query פשוט), אבל השכבה עדיין נשמרת עקבית: **כל** גישת נתונים עוברת דרך ה-service, אף פעם לא ישירות מה-controller. זה **לא** over-engineering — אין ממשק (`interface`) נפרד, אין wrapper, רק מתודה אחת שקוראת ל-repository אחד.
  - `create()` שונה מ-`ResponseEntity<?> create(...)` (עם `try/catch` פנימי) ל-`LeaveRequest create(...)` שזורק `EmployeeNotFoundException`/`InsufficientVacationBalanceException` ומאציל את המיפוי ל-HTTP ל-`GlobalExceptionHandler` — **בדיוק** אותו דפוס כמו `approve()`. עכשיו שתי המתודות הציבוריות היחידות ב-`LeaveRequestService` עקביות זו עם זו: אין יותר שני מנגנוני מיפוי-חריגות מקבילים לאותן חריגות (ראו ה-trade-off שתועד ב-3.1, סעיף אחרון — זה בדיוק האיחוד שתועד שם כ"הייתי עושה עם עוד זמן").
  - נוסף `EmployeeService` חדש וקטן (מתודה ציבורית יחידה, `getAll()`) כדי ש-`EmployeesController` לא ייגע ב-repository ישירות. נשמר מינימלי בכוונה: אין היום שום כלל עסקי על רשימת העובדים, אז אין טעם ביותר ממתודת-מעטפת אחת.

- **Controller:**
  - `LeaveRequestsController` לא מזריק (`inject`) יותר את `LeaveRequestRepository` או `EntityManager`/`PersistenceContext` — כל ארבעת ה-endpoints (`getAll`, `search`, `create`, `approve`) מאצילים ל-`LeaveRequestService` בשורה אחת ומחזירים `ResponseEntity`. אין `try/catch`, אין שאילתה, אין מיון.
    **עדכון (סעיף 3.4):** ה-`ResponseEntity` בכל ארבעת המתודות האלה הוסר בהמשך, כי אף אחת מהן לא נזקקה לו בפועל — ראו 3.4 לפרטים.
  - `EmployeesController` מזריק את `EmployeeService` במקום `EmployeeRepository`.
  - נוסף `@Valid` על `@RequestBody CreateLeaveRequestDto` ב-`create()`, יחד עם `@NotNull` על ארבעת השדות ב-DTO עצמו. זו **ולידציית נוכחות-שדה בלבד** (payload validation, אחריות ה-controller) — במכוון **לא** נוספה ולידציה עסקית כמו "תאריך התחלה לא יכול להיות בעבר" או "endDate אחרי startDate", כי אלה כללים עסקיים ומקומם ב-`LeaveRequestService`, לא ב-annotations על ה-DTO. כשהולידציה נכשלת, Spring מחזיר 400 אוטומטית (ברירת המחדל של `MethodArgumentNotValidException`) בלי צורך ב-handler ייעודי.

**מה לא שונה בכוונה (כדי לא לשבור חוזה/טסטים קיימים):**
- קודי ה-HTTP הקיימים (200/404/400/409) לא השתנו עבור אף endpoint.
- לא נוספה ולידציה עסקית (טווח תאריכים, "לא בעבר" וכו') — זו הרחבת scope שלא התבקשה, וה-task המקורי היה concerned רק מהפרדת שכבות.
- לא נוסף DTO ל-response (ה-controllers עדיין מחזירים ישויות `LeaveRequest`/`Employee` ישירות) — זה שיפור סביר נוסף (ראו הערה קיימת ב-`LeaveRequest.java`) אבל מחוץ ל-scope של המשימה הזו.
  **עדכון (סעיף 3.5):** זה בדיוק מה שהתבקש והתבצע בהמשך — ראו 3.5.

**טסטים שעודכנו/נוספו:**
- `LeaveRequestsTests` הותאם לחוזה החדש של `create()`: מסלולי הכישלון עכשיו נבדקים עם `assertThrows(...)` (בדיוק כמו ש-`LeaveRequestApprovalTests` כבר עשה ל-`approve()`), במקום `result.getStatusCode()`.
- נוסף קובץ חדש, `LeaveRequestsControllerCreateHttpTests` (`@AutoConfigureMockMvc`), שמריץ בקשות HTTP אמיתיות מול `POST /api/leave-requests` ובודק 200/404/400 מקצה-לקצה דרך ה-`DispatcherServlet` וה-`GlobalExceptionHandler` בפועל — מראה `LeaveRequestsControllerApprovalHttpTests` בדיוק: מוודא שקוד הסטטוס נשמר לאחר שהמיפוי הוצא מה-service. נוסף גם טסט ל-400 על payload עם שדה חסר (`create_MissingRequiredField_ReturnsBadRequest`), לכיסוי ה-`@Valid` החדש.
- כל 18 הטסטים (4 ב-`LeaveRequestsTests`, 6 ב-`LeaveRequestApprovalTests`, 4 ב-`LeaveRequestsControllerApprovalHttpTests`, 4 ב-`LeaveRequestsControllerCreateHttpTests`) עוברים מול PostgreSQL אמיתי (Testcontainers).

### 3.4 ניקוי: הסרת `ResponseEntity` ממתודות controller שתמיד מחזירות 200 בלי header מותאם

> **הערה מפורשת: השינוי הזה התבקש במפורש על ידי המשתמש/הסוקר** ("Review all controllers in this project and simplify methods that return `ResponseEntity.ok(body)` when they always return HTTP 200 and set no custom headers. In those cases, return the body directly from the `@RestController`") — לא יוזמת AI עצמאית.

**מה השתנה, ולמה זה בטוח:** ב-Spring MVC, מתודת `@RestController` שמחזירה אובייקט "רגיל" (לא `ResponseEntity`) מוגשת עם סטטוס `200 OK` וה-`Content-Type`/גוף JSON נקבעים באותו `HttpMessageConverter` בדיוק כמו כשעוטפים אותה ב-`ResponseEntity.ok(...)`. חמש המתודות הבאות תמיד ביצעו נתיב הצלחה יחיד ללא סטטוס מותנה וללא header מותאם, ולכן `ResponseEntity` שם היה עטיפה מיותרת:

| Controller | מתודה | לפני | אחרי |
|---|---|---|---|
| `EmployeesController` | `getAll()` | `ResponseEntity<List<Employee>>` | `List<Employee>` |
| `LeaveRequestsController` | `getAll()` | `ResponseEntity<List<LeaveRequest>>` | `List<LeaveRequest>` |
| `LeaveRequestsController` | `search(name)` | `ResponseEntity<List<LeaveRequest>>` | `List<LeaveRequest>` |
| `LeaveRequestsController` | `create(dto)` | `ResponseEntity<LeaveRequest>` | `LeaveRequest` |
| `LeaveRequestsController` | `approve(id)` | `ResponseEntity<LeaveRequest>` | `LeaveRequest` |

הוסרו גם ה-imports המיותרים של `org.springframework.http.ResponseEntity` משני קובצי ה-controller.

**מה נשאר עם `ResponseEntity`, ולמה (לא שונה):**
- **`GlobalExceptionHandler`** — כל מתודת `@ExceptionHandler` שם מחזירה `ResponseEntity<String>` עם סטטוס **שונה בהתאם לסוג החריגה** (404 / 409 / 400) — זה בדיוק המקרה של "status שנבחר באופן מותנה" שצריך `ResponseEntity` כדי לקבוע קוד שאינו 200 כברירת מחדל. לא נגעתי בקובץ הזה.
- לא נמצאו endpoints נוספים בפרויקט שמחזירים `201 Created`, `204 No Content`, header מותאם אישית, או סטטוס מותנה אחר — אילו היו קיימים, היו נשארים עם `ResponseEntity` מאותה סיבה.

**Trade-off:** אין — זהו ניקוי טהור (equivalent refactor). קוד הסטטוס (200 בהצלחה, 404/409/400 בכשל דרך ה-advice), גוף ה-JSON, וה-headers זהים לחלוטין לפני ואחרי; לא בוצע שינוי רוחבי מעבר למה שהתבקש (למשל, לא הוצג DTO חדש ולא שונה שום endpoint אחר).

**טסטים והרצה:** לא נדרש שינוי באף טסט — אף טסט קיים לא הניח הנחות על `ResponseEntity` עצמו (הם בודקים קוד סטטוס דרך `MockMvc`, או קוראים ישירות ל-service). כל 18 הטסטים (`LeaveRequestsTests`, `LeaveRequestApprovalTests`, `LeaveRequestsControllerApprovalHttpTests`, `LeaveRequestsControllerCreateHttpTests`) עברו מול PostgreSQL אמיתי (Testcontainers) לאחר השינוי, וגם `mvn package` (בנייה מלאה, כולל `spring-boot-maven-plugin` repackage) הצליח.

### 3.5 כל endpoint שמחזיר data מחזיר DTO, אף פעם לא ישות JPA ישירות

> **הערה מפורשת: השינוי הזה התבקש במפורש על ידי המשתמש/הסוקר** ("I want every controller endpoint that returns application data to return a DTO, never a JPA entity directly — even when the DTO currently has exactly the same fields as the entity") — לא יוזמת AI עצמאית. הוא הופך במפורש את מה שתועד קודם (בסעיף 3.3) כ"מחוץ ל-scope", ומשלים את ההערה שכבר הייתה קיימת בקוד ב-`LeaveRequest.java` ("Returning DTOs instead is a fair improvement to suggest").

**מה השתנה:**
- נוספו שני response DTOs חדשים תחת `com.example.leavemanagement.dto`:
  - `EmployeeResponseDto` — `{id, name, annualQuota}`, עם factory method סטטי `from(Employee)`.
  - `LeaveRequestResponseDto` — `{id, employeeId, employee, type, startDate, endDate, status, days}`, עם `from(LeaveRequest)` שממפה גם את ה-`employee` המקונן (דרך `EmployeeResponseDto.from(...)`, או `null` אם `request.getEmployee()` הוא `null` — בדיוק כמו היום, למשל בתשובת `create()` שלא טוענת את הקשר).
- שני ה-controllers מבצעים את המיפוי **בשכבת ה-controller עצמה** (לא ב-service): `LeaveRequestsController` ו-`EmployeesController` קוראים ל-service, מקבלים ישות/רשימת ישויות, וממפים ל-DTO/רשימת DTOs לפני ההחזרה. ה-service ממשיך לעבוד עם `LeaveRequest`/`Employee` (ישויות JPA) בדיוק כמו קודם — שום דבר בשכבת ה-service לא השתנה. זו בחירה מכוונת: מיפוי ישות→DTO הוא בעיקר concern של ה-HTTP/presentation layer, לא לוגיקה עסקית, ולכן מקומו ב-controller (עקבי עם ההנחיה הקודמת ששמרה טיפול ב-HTTP רק ב-controllers/exception handlers).
- עודכן ה-comment הישן שהיה על שדה ה-`employee` ב-`LeaveRequest.java`, שתיאר את המצב הקודם ("POC returns entities straight from the controller") ולא היה מדויק יותר לאחר השינוי; ה-`@JsonIgnoreProperties` נשאר במקום כהגנה, למקרה שהישות תסודרר (`serialize`) ישירות אי-פעם בעתיד.

**למה זה בטוח (שימור ה-contract):**
- שני ה-DTOs שוכפלו בדיוק לפי מה שהיה מסודרר (`serialized`) מהישויות עד כה — כולל שמות שדות, וכולל שימור סוג ה-`enum` (`LeaveType`/`LeaveStatus`) על כנו כדי שה-`@JsonFormat(shape = NUMBER)` שמוגדר על ה-`enum` עצמו ימשיך לפעול (השדה עדיין 0/1/2, לא שם ה-`enum`).
- נוספו בדיקות `jsonPath` חדשות (ב-`LeaveRequestsControllerCreateHttpTests.create_WithinQuota_ReturnsOk` וב-`LeaveRequestsControllerApprovalHttpTests.approve_PendingRequest_ReturnsOk`) שמאמתות בפועל, מקצה לקצה דרך `MockMvc`, שגוף התשובה זהה למה שהיה: `id`, `employeeId`, `employee` (`null` ב-`create()`, אובייקט מקונן מלא ב-`approve()`), `type`, `status`, `days`, `startDate`, `endDate`.
- קודי ה-HTTP (200/404/409/400) וה-headers לא השתנו — רק גוף התשובה ה-JSON, וגם הוא נשאר זהה בערכיו.

**אף endpoint לא נדרש לשנות את ה-contract שלו.** לא היה צורך בפשרה כלשהי: שני ה-DTOs שיכפלו את הצורה הקיימת במדויק, כולל ה-`null` המותנה על `employee`.

**Trade-off:** יש כאן קצת קוד boilerplate נוסף (שני מחלקות DTO + מיפוי ב-controller) לעומת החזרת הישות ישירות, אבל זה בדיוק המחיר הרגיל של הפרדת ה-representation החיצוני (API contract) מהמודל הפנימי (JPA entity) — כך שינוי עתידי בישות (שדה חדש, קשר חדש) לא דולף אוטומטית ל-API, וגם אין יותר תלות (אפילו לא תיאורטית/עתידית) ב-`@JsonIgnoreProperties`/`@JsonIgnore` על הישויות כדי למנוע דליפה.

**טסטים והרצה:** לא נכשל אף טסט קיים. כל 18 הטסטים (`LeaveRequestsTests`, `LeaveRequestApprovalTests`, `LeaveRequestsControllerApprovalHttpTests`, `LeaveRequestsControllerCreateHttpTests`) עוברים מול PostgreSQL אמיתי (Testcontainers), כולל בדיקות ה-`jsonPath` החדשות שנוספו לאימות ה-contract. `mvn package` (כולל `spring-boot-maven-plugin` repackage) הצליח.
**עדכון (סעיף 3.6):** שמות שלושת ה-DTOs האלה (`CreateLeaveRequestDto`, `EmployeeResponseDto`, `LeaveRequestResponseDto`) שונו בהמשך ל-`*DtoIn`/`*DtoOut` — ראו 3.6.

### 3.6 הפרדת DTOs לפי כיוון: סיומת `DtoIn` לקלט, `DtoOut` לפלט

> **הערה מפורשת: השינוי הזה התבקש במפורש על ידי המשתמש/הסוקר** ("separate the project's DTOs by direction: use `*DtoIn` for data received from API clients, use `*DtoOut` for data returned to API clients") — לא יוזמת AI עצמאית.

**מה השתנה (rename בלבד, ללא שינוי התנהגות):**

| שם קודם | שם חדש | תפקיד |
|---|---|---|
| `CreateLeaveRequestDto` | `CreateLeaveRequestDtoIn` | קלט ל-`POST /api/leave-requests` |
| `EmployeeResponseDto` | `EmployeeDtoOut` | פלט מ-`GET /api/employees`, ומקונן בתוך `LeaveRequestDtoOut` |
| `LeaveRequestResponseDto` | `LeaveRequestDtoOut` | פלט מ-`GET /api/leave-requests`, `GET /api/leave-requests/search`, `POST /api/leave-requests`, `POST /api/leave-requests/{id}/approve` |

כל שדה, annotation (כולל `@NotNull` על `CreateLeaveRequestDtoIn`), וה-factory method הסטטי `from(...)` על שני ה-`DtoOut` נשארו זהים לחלוטין — זה שינוי שם מחלקה/קובץ בלבד, לא שינוי מבנה. עודכנו כל המקומות שמפנים אליהן: `LeaveRequestsController`, `EmployeesController`, `LeaveRequestService` (חתימת `create(CreateLeaveRequestDtoIn dto)`), ו-3 קובצי טסט (`LeaveRequestsTests`, `LeaveRequestsControllerCreateHttpTests`, `LeaveRequestsControllerApprovalHttpTests`) — כולל comments שהתייחסו לשמות הישנים.

**נקודה אחת נבדקה ואומתה כלא-משפיעה על ה-contract:** שינוי שם המחלקה `CreateLeaveRequestDto` → `CreateLeaveRequestDtoIn` משנה גם את ה-"object name" הפנימי שBean Validation/Spring MVC מייצרים אוטומטית מהמחלקה (`createLeaveRequestDto` → `createLeaveRequestDtoIn`), שמופיע ב-`MethodArgumentNotValidException`. נבדק בפועל (הרצת `create_MissingRequiredField_ReturnsBadRequest`): המחרוזת הזו מופיעה **רק בלוג השרת** (`WARN ... Resolved [MethodArgumentNotValidException: ... object 'createLeaveRequestDtoIn' ...]`), ולא בגוף ה-JSON שחוזר ללקוח — אין ב-`GlobalExceptionHandler` או בקונפיגורציה (`application.yml`) שום `include-binding-errors`/`ErrorController` מותאם שהיה חושף אותה. לכן זו לא נחשבת שינוי ב-contract החיצוני.

**אף endpoint לא נדרש לשנות את ה-contract שלו כדי לתמוך בשינוי השם הזה.** לא נמצא מקרה שבו אחידות השמות (DtoIn/DtoOut) הייתה דורשת לשבור API קיים — כל שלוש המחלקות כבר היו DTOs נפרדים לגמרי לפי כיוון (קלט מול פלט) עוד לפני השינוי (ראו 3.5), כך שזה rename טהור.

**Trade-off:** אין תלות/סיכון חדש. יתרון השם: ברור מיידית מהחתימה של כל endpoint איזה DTO הוא קלט ואיזה פלט, בלי לפתוח את הגוף של המתודה.

**טסטים והרצה:** כל 18 הטסטים עוברים מול PostgreSQL אמיתי (Testcontainers) ללא שינוי בהתנהגות, ו-`mvn package` (כולל `spring-boot-maven-plugin` repackage) הצליח.

## 3.7 Frontend — טופס בקשת חופשה (`LeaveRequestFormComponent`)

### 3.7.1 הרכיב המקורי
נבנה רכיב standalone חדש, `LeaveRequestFormComponent` (`frontend/src/app/leave-requests/leave-request-form/`), עם Reactive Forms (`FormBuilder`/`FormGroup`/`Validators`) במקום להרחיב את הטופס בתוך `LeaveRequestsComponent` עצמו. שתי ולידציות חוצות-שדות מומשו כ-validators נפרדים על ה-`FormGroup` (לא על control בודד, כי כל אחת תלויה בשני השדות `startDate`/`endDate` יחד): `dateRangeValidator` (`startDate <= endDate`) ו-`positiveDurationValidator` (משך מחושב > 0, לפי אותו חישוב inclusive שהשרת משתמש בו — `ChronoUnit.DAYS.between(start, end) + 1`). הרכיב חשוף כ"טיפש" מבחינת HTTP: הוא לא קורא ל-API בעצמו, אלא מקבל `employees`/`submitting`/`submitError` ומפיק `submitted` עם ה-payload התקין — `LeaveRequestsComponent` (ההורה) הוא זה שמבצע את קריאת ה-`POST` בפועל ומעדכן את ה-inputs האלה בהתאם.

### 3.7.2 מודרניזציה ל-Angular Signals

> **הערה מפורשת: כל הרפקטור בסעיף הזה בוצע לפי הנחיה מפורשת ומכוונת שלי (המשתמש/הסוקר) — לא יוזמת AI עצמאית.** ההנחיה הייתה לעדכן **רק** את `LeaveRequestFormComponent` (הפיצ'ר האחרון שנבנה) כך שישתמש ב-Angular Signals APIs מודרניים — `input()`/`input.required()`, `output()`, `viewChild()`/`viewChildren()`, `signal()`/`computed()` — בתחביר ה-control flow החדש בטמפלט (`@if`/`@for`/`@switch` במקום `*ngIf`/`*ngFor`), וב-DI מבוסס `inject()` — **בלי לגעת ברכיבים אחרים**. בהתאם, `LeaveRequestsComponent` (ההורה) לא שונה כלל, כולל ה-`@ViewChild(LeaveRequestFormComponent)` הקלאסי שנשאר שם: query-by-type לא תלוי בסגנון ה-API הפנימי של הילד, כך שהוא ממשיך לעבוד ללא שינוי מול רכיב-ילד עם signal inputs/outputs, וה-binding בטמפלט ההורה (`[employees]`, `[submitting]`, `[submitError]`, `(submitted)`) זהה לחלוטין לפני ואחרי.

**מה השתנה ברכיב עצמו:**
- `@Input() employees/submitting/submitError` → `input<Employee[]>([])` / `input(false)` / `input<string | null>(null)` — signal inputs, נקראים כפונקציה גם בקוד וגם בטמפלט (`employees()`).
- `@Output() submitted = new EventEmitter<...>()` → `submitted = output<CreateLeaveRequestPayload>()`. ה-API הציבורי זהה משני הכיוונים: הרכיב עדיין קורא `.emit(...)`, וההורה עדיין מאזין עם `(submitted)="..."` — אין שינוי נדרש בטמפלט ההורה.
- `constructor(private readonly fb: FormBuilder) {}` → `private readonly fb = inject(FormBuilder);`. השדה `fb` מוגדר **לפני** `form` במחלקה בכוונה — Field initializers רצים לפי סדר ההצהרה, וניסיון לקרוא ל-`this.fb` מתוך אתחול `form` לפני ש-`fb` עצמו אותחל זרק שגיאת קומפילציה (`TS2729: used before its initialization`) בזמן הפיתוח.
- State נגזר (`calculatedDays`, `hasDateRangeError`, `hasInvalidDurationError`, `canSubmit`) מומש כ-`computed()` מעל `formValue`/`formStatus` — שני signals שמגושרים מ-`valueChanges`/`statusChanges` (Observables) דרך `toSignal()` (מ-`@angular/core/rxjs-interop`).
- **מגבלה מתועדת במפורש, לא הוסתרה:** ל-`touched`/`dirty` ב-Reactive Forms **אין** Observable (ולכן אין signal) מקביל — רק `valueChanges`/`statusChanges` חשופים כ-Observables. נוסף signal פנימי, `touchTick`, שמתעדכן ידנית (ב-`markFieldTouched()` שמחובר ל-`(blur)` בטמפלט, וכן ב-`onSubmit()`/`resetForm()`) ומשמש **רק** כ"trigger" ל-`computed` שתלויים במצב touched (`hasDateRangeError`/`hasInvalidDurationError`). `isInvalid()` עצמו נשאר מתודה רגילה שקוראת ישירות את מצב ה-`FormControl` (`control.touched`/`control.dirty`) — לא נעטף ב-`computed`, כי Angular ממילא מריץ קריאות מתודה בטמפלט מחדש בכל מחזור change detection, כך שעטיפה ב-signal לא הייתה מוסיפה שום דבר מלבד complexity.
- נוסף `changeDetection: ChangeDetectionStrategy.OnPush`. זו התאמה טבעית ל-signal inputs/computed (שמיישמים fine-grained reactivity בפני עצמם), ולגיטימית כאן כי כל שינוי ב-state המקומי (blur, submit, reset) קורה דרך event handler שמחובר בטמפלט של הרכיב עצמו — מה שממילא מפעיל change detection תחת `OnPush` — ולא מגיע ממקור אסינכרוני חיצוני ל-Zone שהיה עלול "להיעלם".
- הוסר `CommonModule` מה-`imports` של הרכיב: לאחר המעבר ל-`@if`/`@for` לא נותרה שום תלות ב-directives של `CommonModule` (`[class.invalid]` הוא property binding מובנה של Angular, ו-`[ngValue]` מגיע מ-`ReactiveFormsModule` עצמו, לא מ-`CommonModule`).
- הטמפלט (`.html`) הומר במלואו מ-`*ngIf`/`*ngFor` לתחביר ה-control flow החדש (`@if`/`@for` עם `track`).

**View Queries (`@ViewChild`/`viewChild()`):** לרכיב `LeaveRequestFormComponent` עצמו אין ולא היה שום `@ViewChild` פנימי, ולכן אין כאן מה להמיר — ה-`@ViewChild` היחיד בקוד נמצא ב-`LeaveRequestsComponent` (ההורה, לא הפיצ'ר האחרון), ובהתאם להנחיה המפורשת לא נגעתי בו.

**גרסת Angular ו-developer-preview status:** הפרויקט נעול על `^17.3.0` (מותקן בפועל: `17.3.12`). נבדק ישירות מול `node_modules/@angular/core`: `input()`, `output()` ו-`viewChild()` כולם קיימים וקומפילביליים בגרסה הזו; `input()` עדיין מסומן `@developerPreview` ב-17.3, בעוד ש-`output()`/`viewChild()` כבר יציבים שם. זה מתועד כאן במפורש כי ההנחיה לאמץ אותם ניתנה במודע למרות זאת — כל השלושה הפכו יציבים לחלוטין ב-Angular 18 ואילך, וה-API נשמר forward-compatible ללא שינוי צפוי בקריאה לרכיב.

**טסטים:** `leave-request-form.component.spec.ts` הותאם במלואו ל-API החדש: הגדרת inputs עוברת דרך `fixture.componentRef.setInput(...)` (לא הצבה ישירה ל-property, שכבר לא אפשרית ל-signal input), וקריאה ל-output דרך `.subscribe(...)` במקום `spyOn(component.submitted, 'emit')`. נוספו גם 3 טסטים חדשים: חשיפת `employees()` כ-signal, `hasDateRangeError()` שמתעדכן **רק** אחרי `markFieldTouched()` (מוודא שה-signal לא "מדליף" שגיאה לפני touch), ורינדור `submitError()` בפועל בטמפלט. כל 17 הטסטים (1 ב-`app.component.spec`, 16 ב-`leave-request-form.component.spec`) עוברים ב-ChromeHeadless, וגם `ng build` (production, AOT, עם תחביר ה-control flow החדש) הצליח.

## 4. על מה ויתרתי בגלל הזמן
- ... ומה הייתי עושה עם עוד יום:

## 5. שימוש ב‑AI
### איפה AI עזר (כולל prompts)
1. prompt: "..." → מה קיבלתי ומה עשיתי איתו:
2. ...

### איפה דחיתי/תיקנתי הצעה של AI
- מה AI הציע, למה זה היה שגוי, ומה עשיתי במקום:

### אבטחה
- **SQL Injection ב-`LeaveRequestsController.search()`** (לפני הרפקטור: `backend/src/main/java/.../controller/LeaveRequestsController.java`, המתודה `search`): הקוד בנה שאילתת SQL native על ידי concatenation ישיר של `@RequestParam String name` לתוך מחרוזת ה-SQL (`"... WHERE name LIKE '%" + name + "%'"`), והריץ אותה עם `entityManager.createNativeQuery(sql, ...)`. זו פרצת SQL Injection קלאסית — פרמטר שנשלט לגמרי על ידי הקורא הופך לחלק מהשאילתה עצמה, בלי escaping ובלי binding. זוהתה ותועדה כבר בסעיף 1 למעלה ("הזזתם לשכבת service... לא תוקנה כחלק מהמשימות הללו"), ותוקנה בפועל כחלק מרפקטור 3.3: הוחלפה במתודת derived-query פרמטרית, `LeaveRequestRepository.findByEmployee_NameContainingIgnoreCase(String name)`, ש-Spring Data ממירה ל-`JOIN` + `LIKE` עם bound parameter — אין יותר בניית SQL מקלט משתמש בכל שכבה בקוד.

## 6. הוראות הרצה
- (אם שיניתם משהו מהוראות ה‑README המקורי)

## 7. שיפורים עתידיים (לא מומשו)

> **הבהרה מפורשת:** כל שלושת הסעיפים תחת הכותרת הזו הם **הצעות שהמשתמש/הסוקר העלה לשיקול עתידי בלבד** — לא שינויים שבוצעו, ולא המלצות עצמאיות של ה-AI. שום קובץ קוד לא שונה כתוצאה מהסעיף הזה; זהו תיעוד כוונות לקריאה עתידית, לא רשימת עבודה.

### 7.1 Lombok
השיקול שהועלה: להוסיף את Lombok כדי לצמצם boilerplate חוזר (getters/setters, constructors) שנכתב היום ידנית בכל מחלקות הישויות/DTOs/services, באמצעות annotations **ממוקדות** לפי הצורך — למשל `@Getter` (רק getters), או `@RequiredArgsConstructor` (constructor לשדות `final`, מתאים למשל ל-`LeaveRequestService`/`EmployeeService` שכבר עובדים עם constructor injection) — ולא annotation גורפת אחת שמוחלת אוטומטית על כל מחלקה.

**אזהרה שהועלתה במפורש:** יש להיזהר במיוחד כשמדובר בישויות JPA (`Employee`, `LeaveRequest`). annotations רחבות כמו `@Data` מייצרות גם `equals`/`hashCode`/`toString`, שעלולות להיות בעייתיות על ישויות עם קשרים (`@ManyToOne`/`@OneToMany`) — למשל `toString()`/`equals()`/`hashCode()` אוטומטיים שנוגעים בקולקציית `Employee.leaveRequests` או בקשר החוזר ל-`LeaveRequest.employee` עלולים לגרור recursion מעגלי בין שתי הישויות (אותה בעיית navigation מעגלית שכבר תועדה ב-comment הקיים בקוד), או בעיות עם proxies/lazy loading אם הקשרים ישונו ל-lazy בעתיד. לכן, אם בכלל, מומלצות רק annotations ממוקדות (`@Getter`/`@Setter`) על ישויות — לא `@Data`/`@EqualsAndHashCode`/`@ToString` גורפים.

עדיפות ל-`record` על פני DTO רגיל עם Lombok היכן שמתאים: ל-DTOs פשוטים ו-immutable (כמו `EmployeeResponseDto`/`LeaveRequestResponseDto` שנוספו בסעיף 3.5, שכבר בנויים עם שדות `final` וקונסטרקטור יחיד) `record` הוא הפתרון הטבעי ב-Java 21 (הגרסה שמוגדרת ב-`pom.xml`), ולא דורש תלות חיצונית נוספת כלל.

### 7.2 Resilience4j
השיקול שהועלה: **לא להוסיף את הספרייה עכשיו**, ולא "ליתר ביטחון" — הזרימה היחידה שרגישה ל-concurrency/כשלים בפרויקט כרגע (`LeaveRequestService.approve()`, ראו סעיף 3) פונה אך ורק ל-PostgreSQL דרך Spring Data JPA/Hibernate, בתוך טרנזקציה מקומית אחת עם נעילות פסימיות — אין קריאת רשת יוצאת, HTTP client, או תלות בשירות חיצוני שדורשת timeout/circuit breaker/bulkhead.

אם בעתיד תתווסף תלות אמיתית בשירות חיצוני (API של צד שלישי, שירות אימות, שירות התראות וכו'), לשקול Resilience4j **ממוקד לאותה קריאה הספציפית בלבד** — `TimeLimiter`, `CircuitBreaker`, `Bulkhead` ו/או `Retry` — לפי אופי הכשל שרוצים להגן מפניו (קריאה איטית, שירות שקורס, עומס-יתר, כשל חולף), ולא כתוספת גורפת לכל הפרויקט.

**נקודה קריטית שהועלתה במפורש:** להוסיף `Retry` לפעולה עם side-effect (למשל endpoint שיוצר רשומה, שולח התראה, או מחייב תשלום) **דורש אסטרטגיית idempotency** לפני שמוסיפים את ה-retry עצמו — אחרת retry אחרי timeout עלול לגרום לביצוע כפול של אותה פעולה בפועל (למשל שתי בקשות חופשה שנוצרות מאותה כוונה יחידה של המשתמש, כי הקריאה הראשונה הצליחה בצד השרת אך ה-timeout בצד הלקוח הפעיל retry). הפתרון המקובל: idempotency key שנשלח מהלקוח ונבדק/נשמר בצד השרת לפני ביצוע הפעולה בפועל. זו אותה נקודה שכבר תועדה כ"מה הייתי עושה עם עוד זמן" בסעיף 3 (Trade-offs) לגבי `approve()`.

בקיצור: להעריך כל תלות חיצונית ומצב-כשל שלה **בנפרד**, לפני שמוסיפים את הספרייה בגללה — לא להוסיף אותה כברירת מחדל לכל הפרויקט.

### 7.3 ארגון קוד מונחה-פיצ'ר (Feature-Oriented Packaging)
המבנה הנוכחי מאורגן לפי שכבה (`controller`, `service`, `repository`, `dto`, `exception`, `model`) — סביר לגודל הפרויקט הנוכחי (שני תחומי-פיצ'ר בלבד: עובדים ובקשות חופשה).

השיקול שהועלה: אם הקוד והצוות יגדלו, לשקול מעבר לארגון מונחה-פיצ'ר — חבילה נפרדת לכל feature (למשל `employee/`, `leaverequest/`), שכל אחת מכילה בתוכה את ה-controller, service, repository, DTOs, וחריגות הרלוונטיים לאותו feature בלבד, במקום לפזר כל feature על פני חמש חבילות top-level לפי שכבה.

**למה זה עדיף בהיקף גדול יותר, לפי מה שהועלה:**
- קל יותר למצוא ולשנות את כל הקוד ששייך לפיצ'ר מסוים — אין צורך לקפוץ בין כמה תיקיות-שכבה כדי לראות את כל מה שקשור, למשל, ל-"leave request".
- גבולות בעלות ומודול ברורים יותר — קל יותר להצהיר "צוות X אחראי על `employee/`" מאשר "צוות X אחראי על חלק מ-`controller/`, חלק מ-`service/` וחלק מ-`repository/`".
- מפחית coupling מקרי בין features — קשה יותר "לשאול" בטעות מחלקה פנימית של feature אחר כשהיא חיה בחבילה נפרדת עם גבול ברור, לעומת מצב שבו כל השירותים חיים יחד תחת אותו `service/` package ונגישים בקלות זה לזה.

**הבהרה מפורשת שהתבקשה:** זו החלטת ארגון-קוד שנובעת **ממורכבות הפרויקט/הצוות** (כמות ה-features, כמות המפתחים שעובדים במקביל על הקוד) — **לא** נגזרת אוטומטית מנפח בקשות (traffic/RPS) בזמן ריצה, ששיקול נפרד לגמרי (scaling, ביצועים, תשתית).

</div>
