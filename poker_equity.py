from itertools import combinations
from poker import parse_card, evaluate, card_name


def exact_equity(hero, villain, board):
    """
    Точный расчёт эквити Hero против конкретной руки Villain.

    hero: 2 карты
    villain: 2 карты
    board: от 0 до 5 общих карт

    Возвращает словарь с количеством побед, ничьих,
    поражений и эквити в процентах.
    """

    if len(hero) != 2 or len(villain) != 2:
        raise ValueError("У каждого игрока должно быть ровно 2 карты")

    if len(board) not in (0, 3, 4, 5):
        raise ValueError("Общих карт должно быть 0, 3, 4 или 5")

    known = hero + villain + board

    if len(set(known)) != len(known):
        raise ValueError("Одна и та же карта указана несколько раз")

    deck = [
        (rank, suit)
        for rank in range(2, 15)
        for suit in "shdc"
        if (rank, suit) not in known
    ]

    missing = 5 - len(board)
    wins = ties = losses = 0

    for extra in combinations(deck, missing):
        final_board = board + list(extra)

        hero_rank = evaluate(hero + final_board)
        villain_rank = evaluate(villain + final_board)

        if hero_rank > villain_rank:
            wins += 1
        elif hero_rank < villain_rank:
            losses += 1
        else:
            ties += 1

    total = wins + ties + losses

    equity = (
        (wins + ties / 2) / total * 100
        if total else 0.0
    )

    return {
        "wins": wins,
        "ties": ties,
        "losses": losses,
        "total": total,
        "equity": equity,
    }


def read_hand(prompt, count):
    while True:
        raw = input(prompt).strip().split()

        try:
            cards = [parse_card(item) for item in raw]

            if len(cards) != count:
                print(f"Нужно указать {count} карты.")
                continue

            if len(set(cards)) != len(cards):
                print("Карты не должны повторяться.")
                continue

            return cards

        except ValueError as exc:
            print(exc)


def main():
    print("\nPOKER ASSISTANT — EXACT EQUITY")
    print("Пример карты: As Kh Qd Jc Ts")
    print("Масти: s — пики, h — червы, d — бубны, c — трефы\n")

    hero = read_hand("Твои карты: ", 2)
    villain = read_hand("Карты соперника: ", 2)

    while True:
        try:
            count = int(input("Количество общих карт (0, 3, 4, 5): "))

            if count in (0, 3, 4, 5):
                break
        except ValueError:
            pass

        print("Введи 0, 3, 4 или 5.")

    board = read_hand("Общие карты: ", count) if count else []

    result = exact_equity(hero, villain, board)

    print("\nРЕЗУЛЬТАТ")
    print(f"Победы: {result['wins']}")
    print(f"Ничьи: {result['ties']}")
    print(f"Поражения: {result['losses']}")
    print(f"Рассмотрено вариантов: {result['total']}")
    print(f"Эквити: {result['equity']:.2f}%")


if __name__ == "__main__":
    main()
